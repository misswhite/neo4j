/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.kernel.api;

import org.eclipse.collections.api.list.primitive.LongList;
import org.eclipse.collections.api.set.primitive.LongSet;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.factory.primitive.LongSets;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.neo4j.internal.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.internal.kernel.api.TestUtils.assertDistinct;
import static org.neo4j.internal.kernel.api.TestUtils.concat;
import static org.neo4j.internal.kernel.api.TestUtils.count;
import static org.neo4j.internal.kernel.api.TestUtils.randomBatchWorker;
import static org.neo4j.internal.kernel.api.TestUtils.singleBatchWorker;

public abstract class ParallelNodeCursorTransactionStateTestBase<G extends KernelAPIWriteTestSupport>
        extends KernelAPIWriteTestBase<G>
{

    private static final ToLongFunction<NodeCursor> NODE_GET = NodeCursor::nodeReference;

    @Test
    public void shouldHandleEmptyDatabase() throws TransactionFailureException
    {
        try ( Transaction tx = beginTransaction() )
        {
            try ( NodeCursor cursor = tx.cursors().allocateNodeCursor() )
            {
                Scan<NodeCursor> scan = tx.dataRead().allNodesScan();
                while ( scan.reserveBatch( cursor, 23 ) )
                {
                    assertFalse( cursor.next() );
                }
            }
        }
    }

    @Test
    public void scanShouldNotSeeDeletedNode() throws Exception
    {
        int size = 100;
        Set<Long> created = new HashSet<>( size );
        Set<Long> deleted = new HashSet<>( size );
        try ( Transaction tx = beginTransaction() )
        {
            Write write = tx.dataWrite();
            for ( int i = 0; i < size; i++ )
            {
                created.add( write.nodeCreate() );
                deleted.add( write.nodeCreate() );
            }
            tx.success();
        }

        try ( Transaction tx = beginTransaction() )
        {
            for ( long delete : deleted )
            {
                tx.dataWrite().nodeDelete( delete );
            }

            try ( NodeCursor cursor = tx.cursors().allocateNodeCursor() )
            {
                Scan<NodeCursor> scan = tx.dataRead().allNodesScan();
                Set<Long> seen = new HashSet<>();
                while ( scan.reserveBatch( cursor, 17 ) )
                {
                    while ( cursor.next() )
                    {
                        long nodeId = cursor.nodeReference();
                        assertTrue( seen.add( nodeId ) );
                        assertTrue( created.remove( nodeId ) );
                    }
                }

                assertTrue( created.isEmpty() );
            }
        }
    }

    @Test
    public void scanShouldSeeAddedNodes() throws Exception
    {
        int size = 100;
        MutableLongSet existing = createNodes( size );
        Set<Long> added = new HashSet<>( size );

        try ( Transaction tx = beginTransaction() )
        {
            for ( int i = 0; i < size; i++ )
            {
                added.add( tx.dataWrite().nodeCreate() );
            }

            try ( NodeCursor cursor = tx.cursors().allocateNodeCursor() )
            {
                Scan<NodeCursor> scan = tx.dataRead().allNodesScan();
                Set<Long> seen = new HashSet<>();
                while ( scan.reserveBatch( cursor, 17 ) )
                {
                    while ( cursor.next() )
                    {
                        long nodeId = cursor.nodeReference();
                        assertTrue( seen.add( nodeId ) );
                        assertTrue( existing.remove( nodeId ) || added.remove( nodeId ) );
                    }
                }

                //make sure we have seen all nodes
                assertTrue( existing.isEmpty() );
                assertTrue( added.isEmpty() );
            }
        }
    }

    @Test
    public void shouldReserveBatchFromTxState()
            throws TransactionFailureException, InvalidTransactionTypeKernelException
    {
        try ( Transaction tx = beginTransaction() )
        {
            for ( int i = 0; i < 11; i++ )
            {
                tx.dataWrite().nodeCreate();
            }

            try ( NodeCursor cursor = tx.cursors().allocateNodeCursor() )
            {
                Scan<NodeCursor> scan = tx.dataRead().allNodesScan();
                assertTrue( scan.reserveBatch( cursor, 5 ) );
                assertEquals( 5, count( cursor ) );
                assertTrue( scan.reserveBatch( cursor, 4 ) );
                assertEquals( 4, count( cursor ) );
                assertTrue( scan.reserveBatch( cursor, 6 ) );
                assertEquals( 2, count( cursor ) );
                //now we should have fetched all nodes
                while ( scan.reserveBatch( cursor, 3 ) )
                {
                    assertFalse( cursor.next() );
                }
            }
        }
    }

    @Test
    public void shouldScanAllNodesFromMultipleThreads()
            throws InterruptedException, ExecutionException, TransactionFailureException,
            InvalidTransactionTypeKernelException
    {
        // given
        ExecutorService service = Executors.newFixedThreadPool( 4 );
        CursorFactory cursors = testSupport.kernelToTest().cursors();
        int size = 128;
        LongArrayList ids = new LongArrayList();
        try ( Transaction tx = beginTransaction() )
        {
            Write write = tx.dataWrite();
            for ( int i = 0; i < size; i++ )
            {
                ids.add( write.nodeCreate() );
            }

            Read read = tx.dataRead();
            Scan<NodeCursor> scan = read.allNodesScan();

            // when
            Future<LongList> future1 = service.submit( singleBatchWorker( scan, cursors::allocateNodeCursor, NodeCursor::nodeReference, size / 4 ) );
            Future<LongList> future2 = service.submit( singleBatchWorker( scan, cursors::allocateNodeCursor, NodeCursor::nodeReference, size / 4 ) );
            Future<LongList> future3 = service.submit( singleBatchWorker( scan, cursors::allocateNodeCursor, NodeCursor::nodeReference, size / 4 ) );
            Future<LongList> future4 = service.submit( singleBatchWorker( scan, cursors::allocateNodeCursor, NodeCursor::nodeReference, size / 4 ) );

            // then
            LongList ids1 = future1.get();
            LongList ids2 = future2.get();
            LongList ids3 = future3.get();
            LongList ids4 = future4.get();

            assertDistinct( ids1, ids2, ids3, ids4 );
            LongList concat = concat( ids1, ids2, ids3, ids4 );
            assertEquals( ids.toSortedList(), concat.toSortedList() );
            tx.failure();
        }
        finally
        {
            service.shutdown();
            service.awaitTermination( 1, TimeUnit.MINUTES );
        }
    }

    @Test
    public void shouldScanAllNodesFromMultipleThreadWithBigSizeHints()
            throws InterruptedException, ExecutionException, TransactionFailureException,
            InvalidTransactionTypeKernelException
    {
        // given
        ExecutorService service = Executors.newFixedThreadPool( 4 );
        CursorFactory cursors = testSupport.kernelToTest().cursors();
        int size = 128;
        LongArrayList ids = new LongArrayList();
        try ( Transaction tx = beginTransaction() )
        {
            Write write = tx.dataWrite();
            for ( int i = 0; i < size; i++ )
            {
                ids.add( write.nodeCreate() );
            }

            Read read = tx.dataRead();
            Scan<NodeCursor> scan = read.allNodesScan();

            // when
            Supplier<NodeCursor> allocateCursor = cursors::allocateNodeCursor;
            Future<LongList> future1 = service.submit( singleBatchWorker( scan, allocateCursor, NODE_GET, 100 ) );
            Future<LongList> future2 = service.submit( singleBatchWorker( scan, allocateCursor, NODE_GET, 100 ) );
            Future<LongList> future3 = service.submit( singleBatchWorker( scan, allocateCursor, NODE_GET, 100 ) );
            Future<LongList> future4 = service.submit( singleBatchWorker( scan, allocateCursor, NODE_GET, 100 ) );

            // then
            LongList ids1 = future1.get();
            LongList ids2 = future2.get();
            LongList ids3 = future3.get();
            LongList ids4 = future4.get();

            assertDistinct( ids1, ids2, ids3, ids4 );
            LongList concat = concat( ids1, ids2, ids3, ids4 );
            assertEquals( ids.toSortedList(), concat.toSortedList() );
        }
        finally
        {
            service.shutdown();
            service.awaitTermination( 1, TimeUnit.MINUTES );
        }
    }

    @Test
    public void shouldScanAllNodesFromRandomlySizedWorkers()
            throws InterruptedException, TransactionFailureException,
            InvalidTransactionTypeKernelException
    {
        // given
        ExecutorService service = Executors.newFixedThreadPool( 4 );
        int size = 128;
        LongArrayList ids = new LongArrayList();

        try ( Transaction tx = beginTransaction() )
        {
            Write write = tx.dataWrite();
            for ( int i = 0; i < size; i++ )
            {
                ids.add( write.nodeCreate() );
            }

            Read read = tx.dataRead();
            Scan<NodeCursor> scan = read.allNodesScan();
            CursorFactory cursors = testSupport.kernelToTest().cursors();

            // when
            ArrayList<Future<LongList>> futures = new ArrayList<>();
            for ( int i = 0; i < 10; i++ )
            {
                futures.add( service.submit( randomBatchWorker( scan, cursors::allocateNodeCursor, NODE_GET ) ) );
            }

            // then
            List<LongList> lists = futures.stream().map( TestUtils::unsafeGet ).collect( Collectors.toList() );

            assertDistinct( lists );
            LongList concat = concat( lists );

            assertEquals( ids.toSortedList(), concat.toSortedList() );
            tx.failure();
        }
        finally
        {
            service.shutdown();
            service.awaitTermination( 1, TimeUnit.MINUTES );
        }
    }

    @Test
    public void parallelTxStateScanStressTest() throws InvalidTransactionTypeKernelException, TransactionFailureException, InterruptedException
    {
        LongSet existingNodes = createNodes( 77 );
        int workers = Runtime.getRuntime().availableProcessors();
        ExecutorService threadPool = Executors.newFixedThreadPool( workers );
        CursorFactory cursors = testSupport.kernelToTest().cursors();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        try
        {
            for ( int i = 0; i < 1000; i++ )
            {
                MutableLongSet allNodes = LongSets.mutable.withAll( existingNodes );
                try ( Transaction tx = beginTransaction() )
                {
                    int nodeInTx = random.nextInt( 100 );
                    for ( int j = 0; j < nodeInTx; j++ )
                    {
                        allNodes.add( tx.dataWrite().nodeCreate() );
                    }

                    Scan<NodeCursor> scan = tx.dataRead().allNodesScan();

                    List<Future<LongList>> futures = new ArrayList<>( workers );
                    for ( int j = 0; j < workers; j++ )
                    {
                        futures.add( threadPool.submit( randomBatchWorker( scan, cursors::allocateNodeCursor, NODE_GET ) ) );
                    }

                    List<LongList> lists =
                            futures.stream().map( TestUtils::unsafeGet ).collect( Collectors.toList() );

                    assertDistinct( lists );
                    LongList concat = concat( lists );
                    assertEquals(
                            String.format( "nodes=%d, seen=%d, all=%d", nodeInTx, concat.size(), allNodes.size() ),
                            allNodes, LongSets.immutable.withAll( concat ) );
                    assertEquals( String.format( "nodes=%d", nodeInTx ), allNodes.size(), concat.size() );
                    tx.failure();
                }
            }
        }
        finally
        {
            threadPool.shutdown();
            threadPool.awaitTermination( 1, TimeUnit.MINUTES );
        }
    }

    private MutableLongSet createNodes( int size )
            throws TransactionFailureException, InvalidTransactionTypeKernelException
    {
        MutableLongSet nodes = LongSets.mutable.empty();
        try ( Transaction tx = beginTransaction() )
        {
            Write write = tx.dataWrite();
            for ( int i = 0; i < size; i++ )
            {
                nodes.add( write.nodeCreate() );
            }
            tx.success();
        }
        return nodes;
    }
}