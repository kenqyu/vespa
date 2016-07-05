// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.persistence;

import com.google.common.collect.ImmutableList;
import com.yahoo.path.Path;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.recipes.CuratorCounter;
import com.yahoo.vespa.curator.transaction.CuratorTransaction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thius encapsulated the curator database of the node repo.
 * It serves reads from an in-memory cache of the content which is invalidated when changed on another node
 * using a global, shared counter. The counter is updated on all write operations, ensured by wrapping write
 * operations in a 2pc transaction containing the counter update.
 *
 * @author bratseth
 */
public class CuratorDatabase {

    private final Curator curator;

    /** A shared atomic counter which is incremented every time we write to the curator database */
    private final CuratorCounter changeGenerationCounter;

    /** A partial cache of the Curator database, which is only valid if generations match */
    private final AtomicReference<CuratorDatabaseCache> cache = new AtomicReference<>();

    /** Whether we should return data from the cache or always read fro ZooKeeper */
    private final boolean useCache;

    /**
     * All keys, to allow reentrancy.
     * This will grow forever with the number of applications seen, but this should be too slow to be a problem.
     */
    private final ConcurrentHashMap<Path, CuratorMutex> locks = new ConcurrentHashMap<>();

    /**
     * Creates a curator database
     *
     * @param curator the curator instance
     * @param root the file system root of the db
     */
    public CuratorDatabase(Curator curator, Path root, boolean useCache) {
        this.useCache = useCache;
        this.curator = curator;
        changeGenerationCounter = new CuratorCounter(curator, root.append("changeCounter").getAbsolute());
        cache.set(newCache(changeGenerationCounter.get()));
    }

    /** Create a reentrant lock */
    // Locks are not cached in the in-memory state
    public CuratorMutex lock(Path path) {
        CuratorMutex lock = locks.computeIfAbsent(path, (pathArg) -> new CuratorMutex(pathArg.getAbsolute(), curator.framework()));
        lock.acquire();
        return lock;

    }

    // --------- Write operations ------------------------------------------------------------------------------
    // These must either create a nested transaction ending in a counter increment or not depend on prior state

    /**
     * Creates a new curator transaction against this database and adds it to the given nested transaction.
     * Important: It is the nested transaction which must be committed - never the curator transaction directly.
     */
    public CuratorTransaction newCuratorTransactionIn(NestedTransaction transaction) {
        // Add a counting transaction first, to make sure we always invalidate the current state on any transaction commit
        transaction.add(new EagerCountingCuratorTransaction(changeGenerationCounter), CuratorTransaction.class);
        CuratorTransaction curatorTransaction = new CuratorTransaction(curator);
        transaction.add(curatorTransaction);
        return curatorTransaction;
    }

    /** Creates a path in curator and all its parents as necessary. If the path already exists this does nothing. */
    // As this operation does not depend on the prior state we do not need to increment the write counter
    public void create(Path path) {
        curator.create(path);
    }

    // --------- Read operations -------------------------------------------------------------------------------
    // These can read from the memory file system, which accurately mirrors the ZooKeeper content IF

    /** Returns the immediate, local names of the children under this node in any order */
    public List<String> getChildren(Path path) {
        CuratorDatabaseCache cache = getCache();
        List<String> children = cache.children(path);
        if (children == null) { // children are not in this cache - get and add
            children = curator.getChildren(path);
            cache.addChildren(path, children);
        }
        return children;
    }

    public Optional<byte[]> getData(Path path) {
        CuratorDatabaseCache cache = getCache();
        Optional<byte[]> data = cache.data(path);
        if (data == null) { // data is not in this cache - get and add
            data = curator.getData(path);
            cache.addData(path, data);
        }
        return data;
    }

    private CuratorDatabaseCache getCache() {
        CuratorDatabaseCache cache = this.cache.get();
        long currentCuratorGeneration = changeGenerationCounter.get();
        if (currentCuratorGeneration != cache.generation()) { // current cache is invalid - start new
            cache = newCache(currentCuratorGeneration);
            this.cache.set(cache);
        }
        return cache;
    }

    /** Caches must only be instantiated using this method */
    private CuratorDatabaseCache newCache(long generation) {
        return useCache ? new CuratorDatabaseCache(generation) : new DeactivatedCache(generation);
    }

    /**
     * A thread safe partial snapshot of the curator database content with a given generation.
     * Note that a snapshot is not necessarily consistent - consistency is handled by pessimistic and optimistic locking
     * in other layers.
     * This is merely what Curator returned at various points in time it had the counter at this generation.
     */
    private static class CuratorDatabaseCache {

        private final long generation;

        // The data of this partial state mirror. The amount of curator state mirrored in this may grow
        // over time by multiple threads. Growing is the only operation permitted by this.
        // The content of the map is immutable.
        private final Map<Path, List<String>> children = new ConcurrentHashMap<>();
        private final Map<Path, Optional<byte[]>> data = new ConcurrentHashMap<>();

        /** Create an empty snapshot at a given generation (as empty snapshot is a valid "partial snapshot" */
        public CuratorDatabaseCache(long generation) {
            this.generation = generation;
        }

        public long generation() { return generation; }

        /**
         * Returns the children of this path, which may be empty.
         * Returns null only if it is not present in this state mirror
         */
        public List<String> children(Path path) { return children.get(path); }

        public void addChildren(Path path, List<String> childrenAtPath) {
            if (children.containsKey(path)) throw new RuntimeException("Programming error");
            children.put(path, ImmutableList.copyOf(childrenAtPath));
        }

        /**
         * Returns the content of this child - which may be empty.
         * Returns null only if it is not present in this state mirror
         */
        public Optional<byte[]> data(Path path) {
            Optional<byte[]> dataAtPath = data.get(path);
            if (dataAtPath == null) return null;
            return dataAtPath.map(d -> Arrays.copyOf(d, d.length));
        }

        public void addData(Path path, Optional<byte[]> dataAtPath) {
            if (data.containsKey(path)) throw new RuntimeException("Programming error");
            data.put(path, dataAtPath);
        }

    }

    /** An implementation of the curator database cache which does no caching */
    private static class DeactivatedCache extends CuratorDatabaseCache {

        public DeactivatedCache(long generation) { super(generation); }

        @Override
        public List<String> children(Path path) { return null; }

        @Override
        public void addChildren(Path path, List<String> childrenAtPath) {}

        @Override
        public Optional<byte[]> data(Path path) { return null; }

        @Override
        public void addData(Path path, Optional<byte[]> dataAtPath) {}

    }

}
