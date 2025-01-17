/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.util;

import static org.apache.iceberg.types.Types.NestedField.required;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.stream.StreamSupport;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TestHelpers;
import org.apache.iceberg.TestTables;
import org.apache.iceberg.types.Types;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestSnapshotUtil {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  // Schema passed to create tables
  public static final Schema SCHEMA =
      new Schema(
          required(3, "id", Types.IntegerType.get()), required(4, "data", Types.StringType.get()));

  // Partition spec used to create tables
  protected static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).build();

  protected File tableDir = null;
  protected File metadataDir = null;
  public TestTables.TestTable table = null;

  static final DataFile FILE_A =
      DataFiles.builder(SPEC)
          .withPath("/path/to/data-a.parquet")
          .withFileSizeInBytes(10)
          .withRecordCount(1)
          .build();

  private long snapshotBaseTimestamp;
  private long snapshotBaseId;
  private long snapshotBranchId;
  private long snapshotMain1Id;
  private long snapshotMain2Id;
  private long snapshotFork0Id;
  private long snapshotFork1Id;
  private long snapshotFork2Id;

  private Snapshot appendFileTo(String branch) {
    table.newFastAppend().appendFile(FILE_A).toBranch(branch).commit();
    return table.snapshot(branch);
  }

  private Snapshot appendFileToMain() {
    return appendFileTo(SnapshotRef.MAIN_BRANCH);
  }

  @Before
  public void before() throws Exception {
    this.tableDir = temp.newFolder();
    tableDir.delete(); // created by table create

    this.metadataDir = new File(tableDir, "metadata");

    this.table = TestTables.create(tableDir, "test", SCHEMA, SPEC, 2);
    Snapshot snapshotBase = appendFileToMain();
    this.snapshotBaseId = snapshotBase.snapshotId();
    this.snapshotBaseTimestamp = snapshotBase.timestampMillis();
    TestHelpers.waitUntilAfter(snapshotBaseTimestamp);

    this.snapshotMain1Id = appendFileToMain().snapshotId();
    this.snapshotMain2Id = appendFileToMain().snapshotId();

    String branchName = "b1";
    table.manageSnapshots().createBranch(branchName, snapshotBaseId).commit();
    this.snapshotBranchId = appendFileTo(branchName).snapshotId();

    // Create a branch that leads back to an expired snapshot
    String forkBranch = "fork";
    table.manageSnapshots().createBranch(forkBranch, snapshotBaseId).commit();
    this.snapshotFork0Id = appendFileTo(forkBranch).snapshotId();
    this.snapshotFork1Id = appendFileTo(forkBranch).snapshotId();
    this.snapshotFork2Id = appendFileTo(forkBranch).snapshotId();
    table.expireSnapshots().expireSnapshotId(snapshotFork0Id).commit();
  }

  @After
  public void cleanupTables() {
    TestTables.clearTables();
  }

  @Test
  public void isParentAncestorOf() {
    Assert.assertTrue(SnapshotUtil.isParentAncestorOf(table, snapshotMain1Id, snapshotBaseId));
    Assert.assertFalse(SnapshotUtil.isParentAncestorOf(table, snapshotBranchId, snapshotMain1Id));
    Assert.assertTrue(SnapshotUtil.isParentAncestorOf(table, snapshotFork2Id, snapshotFork0Id));
  }

  @Test
  public void isAncestorOf() {
    Assert.assertTrue(SnapshotUtil.isAncestorOf(table, snapshotMain1Id, snapshotBaseId));
    Assert.assertFalse(SnapshotUtil.isAncestorOf(table, snapshotBranchId, snapshotMain1Id));
    Assert.assertFalse(SnapshotUtil.isAncestorOf(table, snapshotFork2Id, snapshotFork0Id));

    Assert.assertTrue(SnapshotUtil.isAncestorOf(table, snapshotMain1Id));
    Assert.assertFalse(SnapshotUtil.isAncestorOf(table, snapshotBranchId));
  }

  @Test
  public void currentAncestors() {
    Iterable<Snapshot> snapshots = SnapshotUtil.currentAncestors(table);
    expectedSnapshots(new long[] {snapshotMain2Id, snapshotMain1Id, snapshotBaseId}, snapshots);

    List<Long> snapshotList = SnapshotUtil.currentAncestorIds(table);
    Assert.assertArrayEquals(
        new Long[] {snapshotMain2Id, snapshotMain1Id, snapshotBaseId},
        snapshotList.toArray(new Long[0]));
  }

  @Test
  public void oldestAncestor() {
    Snapshot snapshot = SnapshotUtil.oldestAncestor(table);
    Assert.assertEquals(snapshotBaseId, snapshot.snapshotId());

    snapshot = SnapshotUtil.oldestAncestorOf(table, snapshotMain2Id);
    Assert.assertEquals(snapshotBaseId, snapshot.snapshotId());

    snapshot = SnapshotUtil.oldestAncestorAfter(table, snapshotBaseTimestamp + 1);
    Assert.assertEquals(snapshotMain1Id, snapshot.snapshotId());
  }

  @Test
  public void snapshotsBetween() {
    List<Long> snapshotIdsBetween =
        SnapshotUtil.snapshotIdsBetween(table, snapshotBaseId, snapshotMain2Id);
    Assert.assertArrayEquals(
        new Long[] {snapshotMain2Id, snapshotMain1Id}, snapshotIdsBetween.toArray(new Long[0]));

    Iterable<Snapshot> ancestorsBetween =
        SnapshotUtil.ancestorsBetween(table, snapshotMain2Id, snapshotMain1Id);
    expectedSnapshots(new long[] {snapshotMain2Id}, ancestorsBetween);

    ancestorsBetween = SnapshotUtil.ancestorsBetween(table, snapshotMain2Id, snapshotBranchId);
    expectedSnapshots(
        new long[] {snapshotMain2Id, snapshotMain1Id, snapshotBaseId}, ancestorsBetween);
  }

  @Test
  public void ancestorsOf() {
    Iterable<Snapshot> snapshots = SnapshotUtil.ancestorsOf(snapshotFork2Id, table::snapshot);
    expectedSnapshots(new long[] {snapshotFork2Id, snapshotFork1Id}, snapshots);

    Iterator<Snapshot> snapshotIter = snapshots.iterator();
    while (snapshotIter.hasNext()) {
      snapshotIter.next();
    }

    // Once snapshot iterator has been exhausted, call hasNext again to make sure it is stable.
    Assertions.assertThat(snapshotIter).isExhausted();
  }

  private void expectedSnapshots(long[] snapshotIdExpected, Iterable<Snapshot> snapshotsActual) {
    long[] actualSnapshots =
        StreamSupport.stream(snapshotsActual.spliterator(), false)
            .mapToLong(Snapshot::snapshotId)
            .toArray();
    Assert.assertArrayEquals(snapshotIdExpected, actualSnapshots);
  }
}
