/*******************************************************************************
 * Copyright 2016 vanilladb.org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.elasql.procedure.calvin;

import java.sql.Connection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.elasql.cache.CachedRecord;
import org.elasql.cache.calvin.CalvinCacheMgr;
import org.elasql.cache.calvin.CalvinPostOffice;
import org.elasql.procedure.DdStoredProcedure;
import org.elasql.remote.groupcomm.TupleSet;
import org.elasql.server.Elasql;
import org.elasql.server.migration.MigrationManager;
import org.elasql.sql.RecordKey;
import org.elasql.storage.metadata.PartitionMetaMgr;
import org.elasql.storage.tx.concurrency.ConservativeOrderedCcMgr;
import org.elasql.storage.tx.recovery.DdRecoveryMgr;
import org.vanilladb.core.remote.storedprocedure.SpResultSet;
import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.sql.IntegerConstant;
import org.vanilladb.core.sql.storedprocedure.StoredProcedureParamHelper;
import org.vanilladb.core.storage.tx.Transaction;

public abstract class CalvinStoredProcedure<H extends StoredProcedureParamHelper> implements DdStoredProcedure {

	// For simulating pull request
	private static final RecordKey PULL_REQUEST_KEY;
	private static final String DUMMY_FIELD1 = "dummy_field1";
	private static final String DUMMY_FIELD2 = "dummy_field2";

	static {
		// Create a pull key
		Map<String, Constant> keyEntryMap = new HashMap<String, Constant>();
		keyEntryMap.put(DUMMY_FIELD1, new IntegerConstant(0));
		PULL_REQUEST_KEY = new RecordKey("notification", keyEntryMap);
	}

	// Protected resource
	protected Transaction tx;
	protected long txNum;
	protected H paramHelper;
	protected int localNodeId;
	protected CalvinCacheMgr cacheMgr;

	// Participants
	// Active Participants: Nodes that need to write records locally
	// Passive Participants: Nodes that only need to read records and push
	private Set<Integer> activeParticipants = new HashSet<Integer>();
	private boolean isActiveParticipant, isPassiveParticipant;

	// For read-only transactions to choose one node as a active participant
	private int mostReadsNode = 0;
	private int[] readsPerNodes = new int[PartitionMetaMgr.NUM_PARTITIONS];

	// Record keys
	// XXX: Do we need table-level locks ?
	// XXX: We assume the fully replicated keys are read-only
	private Set<RecordKey> localReadKeys = new HashSet<RecordKey>();
	private Set<RecordKey> fullyRepKeys = new HashSet<RecordKey>();
	private Set<RecordKey> localWriteKeys = new HashSet<RecordKey>();
	private Set<RecordKey> localInsertKeys = new HashSet<RecordKey>();
	private Set<RecordKey> remoteReadKeys = new HashSet<RecordKey>();

	// Migration
	private MigrationManager migraMgr = Elasql.migrationMgr();
	private boolean isSourceNode = (localNodeId == migraMgr.getSourcePartition());
	private boolean isDestNode = (localNodeId == migraMgr.getDestPartition());
	private Set<RecordKey> pullKeys = new HashSet<RecordKey>();
	private Set<RecordKey> readKeysInMigration = new HashSet<RecordKey>();
	private Set<RecordKey> writeKeysInMigration = new HashSet<RecordKey>();
	private Set<RecordKey> keysForBGPush = new HashSet<RecordKey>();
	protected boolean isExecutingInSrc = true;
	private boolean isInMigrating = false, isAnalyzing = false;
	private boolean activePulling = false;

	public CalvinStoredProcedure(long txNum, H paramHelper) {
		this.txNum = txNum;
		this.paramHelper = paramHelper;
		this.localNodeId = Elasql.serverId();

		if (paramHelper == null)
			throw new NullPointerException("paramHelper should not be null");
	}

	/*******************
	 * Abstract methods
	 *******************/

	/**
	 * Prepare the RecordKey for each record to be used in this stored
	 * procedure. Use the {@link #addReadKey(RecordKey)},
	 * {@link #addWriteKey(RecordKey)} method to add keys.
	 */
	protected abstract void prepareKeys();

	protected abstract void executeSql(Map<RecordKey, CachedRecord> readings);

	/**********************
	 * implemented methods
	 **********************/

	public void prepare(Object... pars) {
		// check if this transaction is in a migration period
		isInMigrating = migraMgr.isMigrating();
		isAnalyzing = migraMgr.isAnalyzing();

		// prepare parameters
		paramHelper.prepareParameters(pars);

		// create a transaction
		boolean isReadOnly = paramHelper.isReadOnly();
		tx = Elasql.txMgr().newTransaction(Connection.TRANSACTION_SERIALIZABLE, isReadOnly, txNum);
		tx.addLifecycleListener(new DdRecoveryMgr(tx.getTransactionNumber()));

		// prepare keys
		prepareKeys();

		// if there is no active participant (e.g. read-only transaction),
		// choose the one with most readings as the only active participant.
		if (activeParticipants.isEmpty())
			activeParticipants.add(mostReadsNode);

		// Decide the role
		if (activeParticipants.contains(localNodeId))
			isActiveParticipant = true;
		else if (!localReadKeys.isEmpty())
			isPassiveParticipant = true;

		if (isInMigrating) {

			// The one executing the tx needs to take care the data in the
			// migration range:
			if ((isExecutingInSrc && isSourceNode) || (!isExecutingInSrc && isDestNode)) {
				localReadKeys.addAll(readKeysInMigration);
				localWriteKeys.addAll(writeKeysInMigration);
			}
			if (!isExecutingInSrc) {
				if (isSourceNode)
					remoteReadKeys.addAll(readKeysInMigration);
				if (isDestNode)
					localWriteKeys.addAll(pullKeys);
			}

			// Other nodes should treat the source or the dest node as an active
			// participant ?
			if (!writeKeysInMigration.isEmpty()) {
				if (isExecutingInSrc) {
					activeParticipants.add(migraMgr.getSourcePartition());
				} else {
					activeParticipants.add(migraMgr.getDestPartition());
				}
			}

			// Check if we need to pull data
			if (!isExecutingInSrc && !pullKeys.isEmpty()) {
				migraMgr.setRecordMigrated(pullKeys);
				activePulling = true;
			}

			// Add the inserted keys to the candidates for BG pushes
			if (isExecutingInSrc && isSourceNode) {
				for (RecordKey key : keysForBGPush)
					migraMgr.addNewInsertKey(key);
			}

			// if (isBgPush)
			// bgCount.addAndGet(pullKeys.size());
			// else
			// fgCount.addAndGet(pullKeys.size());
		} else if (isAnalyzing) {
			// Add the inserted keys to the candidates for BG pushes
			if (isSourceNode) {
				for (RecordKey key : keysForBGPush)
					migraMgr.addNewInsertKey(key);
			}
		}

		// for the cache layer
		CalvinPostOffice postOffice = (CalvinPostOffice) Elasql.remoteRecReceiver();
		if (isParticipated()) {
			// create a cache manager
			if (remoteReadKeys.isEmpty())
				cacheMgr = postOffice.createCacheMgr(tx, false);
			else
				cacheMgr = postOffice.createCacheMgr(tx, true);
		} else {
			postOffice.skipTransaction(txNum);
		}
	}

	public void bookConservativeLocks() {
		ConservativeOrderedCcMgr ccMgr = (ConservativeOrderedCcMgr) tx.concurrencyMgr();

		ccMgr.bookReadKeys(localReadKeys);
		ccMgr.bookWriteKeys(localWriteKeys);
		ccMgr.bookWriteKeys(localInsertKeys);
	}

	private void getConservativeLocks() {
		ConservativeOrderedCcMgr ccMgr = (ConservativeOrderedCcMgr) tx.concurrencyMgr();

		ccMgr.requestLocks();
	}

	@Override
	public SpResultSet execute() {
		try {
			// Get conservative locks it has asked before
			getConservativeLocks();

			// Execute transaction
			executeTransactionLogic();

			// Flush the cached records
			cacheMgr.flush();

			// The transaction finishes normally
			tx.commit();
			paramHelper.setCommitted(true);

			// Something might be done after committing
			afterCommit();

		} catch (Exception e) {
			e.printStackTrace();
			tx.rollback();
			paramHelper.setCommitted(false);
		} finally {
			// Clean the cache
			cacheMgr.notifyTxCommitted();
		}

		return paramHelper.createResultSet();
	}

	public boolean isParticipated() {
		return isActiveParticipant || isPassiveParticipant;
	}

	public boolean willResponseToClients() {
		return isActiveParticipant;
	}

	@Override
	public boolean isReadOnly() {
		return paramHelper.isReadOnly();
	}

	/**
	 * This method will be called by execute(). The default implementation of
	 * this method follows the steps described by Calvin paper.
	 */
	protected void executeTransactionLogic() {
		// Read the local records
		Map<RecordKey, CachedRecord> readings = performLocalRead();

		// Push local records to the needed remote nodes
		pushReadingsToRemotes(readings);

		// Passive participants stops here
		if (isPassiveParticipant)
			return;

		// Read the remote records
		collectRemoteReadings(readings);

		// Write the local records
		executeSql(readings);
	}

	protected void addReadKey(RecordKey readKey) {
		// Check if it is a fully replicated key
		if (Elasql.partitionMetaMgr().isFullyReplicated(readKey)) {
			fullyRepKeys.add(readKey);
			return;
		}

		// Check which node has the corresponding record
		int nodeId = Elasql.partitionMetaMgr().getPartition(readKey);
		if (nodeId == localNodeId)
			localReadKeys.add(readKey);
		else
			remoteReadKeys.add(readKey);

		// Record who is the node with most readings
		readsPerNodes[nodeId]++;
		if (readsPerNodes[nodeId] > readsPerNodes[mostReadsNode])
			mostReadsNode = nodeId;
	}

	protected void addWriteKey(RecordKey writeKey) {
		// Check which node has the corresponding record
		int nodeId = Elasql.partitionMetaMgr().getPartition(writeKey);
		if (nodeId == localNodeId)
			localWriteKeys.add(writeKey);
		activeParticipants.add(nodeId);
	}

	protected void addInsertKey(RecordKey insertKey) {
		// Check which node has the corresponding record
		int nodeId = Elasql.partitionMetaMgr().getPartition(insertKey);
		if (nodeId == localNodeId)
			localInsertKeys.add(insertKey);
		activeParticipants.add(nodeId);
	}

	protected void update(RecordKey key, CachedRecord rec) {
		if (localWriteKeys.contains(key))
			cacheMgr.update(key, rec);
	}

	protected void insert(RecordKey key, Map<String, Constant> fldVals) {
		if (localInsertKeys.contains(key))
			cacheMgr.insert(key, fldVals);
	}

	protected void delete(RecordKey key) {
		// XXX: Do we need a 'localDeleteKeys' for this ?
		if (localWriteKeys.contains(key))
			cacheMgr.delete(key);
	}

	protected void afterCommit() {
		// do nothing
	}

	protected void waitForPullRequest() {
		CachedRecord rec = cacheMgr.readFromRemote(PULL_REQUEST_KEY);
		// CachedRecord rec = cacheMgr.read(PULL_REQUEST_KEY, txNum, tx, false);
		int value = (int) rec.getVal(DUMMY_FIELD1).asJavaVal();
		if (value != 0)
			throw new RuntimeException("something wrong for the pull request of tx." + txNum);
	}

	protected void sendAPullRequest(int nodeId) {
		Map<String, Constant> fldVals = new HashMap<String, Constant>();
		fldVals.put(DUMMY_FIELD1, new IntegerConstant(0));
		fldVals.put(DUMMY_FIELD2, new IntegerConstant(0));
		CachedRecord rec = new CachedRecord(fldVals);
		rec.setSrcTxNum(txNum);

		TupleSet ts = new TupleSet(-1);
		ts.addTuple(PULL_REQUEST_KEY, txNum, txNum, rec);
		Elasql.connectionMgr().pushTupleSet(nodeId, ts);
	}

	private Map<RecordKey, CachedRecord> performLocalRead() {
		Map<RecordKey, CachedRecord> localReadings = new HashMap<RecordKey, CachedRecord>();

		// Read local records (for both active or passive participants)
		for (RecordKey k : localReadKeys) {
			CachedRecord rec = cacheMgr.readFromLocal(k);
			localReadings.put(k, rec);
		}

		// Read the fully replicated records (for only active participants)
		if (isActiveParticipant) {
			for (RecordKey k : fullyRepKeys) {
				CachedRecord rec = cacheMgr.readFromLocal(k);
				localReadings.put(k, rec);
			}
		}

		return localReadings;
	}

	private void pushReadingsToRemotes(Map<RecordKey, CachedRecord> readings) {
		// If there is only one active participant, and you are that one,
		// return immediately.
		if (activeParticipants.size() < 2 && isActiveParticipant)
			return;

		TupleSet ts = new TupleSet(-1);
		if (!readings.isEmpty()) {
			// Construct pushing tuple set
			for (Entry<RecordKey, CachedRecord> e : readings.entrySet()) {
				if (!fullyRepKeys.contains(e.getKey()))
					ts.addTuple(e.getKey(), txNum, txNum, e.getValue());
			}

			// Push to all active participants
			for (Integer n : activeParticipants)
				if (n != localNodeId)
					Elasql.connectionMgr().pushTupleSet(n, ts);
		}
	}

	private void collectRemoteReadings(Map<RecordKey, CachedRecord> readingCache) {
		// Read remote records
		for (RecordKey k : remoteReadKeys) {
			CachedRecord rec = cacheMgr.readFromRemote(k);
			readingCache.put(k, rec);
		}
	}
}
