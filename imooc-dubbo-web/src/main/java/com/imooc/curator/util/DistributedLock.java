package com.imooc.curator.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;

import java.util.concurrent.CountDownLatch;

/**
 * Created with IntelliJ IDEA.
 *
 * @author: chenzhongyong@cecdat.com
 * Date: 2018/5/7
 * Time: 10:45
 */
@Slf4j
public class DistributedLock {

    private CuratorFramework client = null;

    //用于挂起当前请求，并且等待上一个分布式锁释放
    private static CountDownLatch zkLockLatch = new CountDownLatch(1);

    //分布式锁的总结点名
    public static final String ZK_LOCK_PROJECT = "imooc-locks";

    public static final String DISTRIBUTED_LOCK = "distributed_lock";

    public DistributedLock(CuratorFramework client) {
        this.client = client;
    }

    public void init() {
        //使用命名空间
        client = client.usingNamespace("ZKLocks-Namespace");


        /**
         * ZKLocks-Namespace
         *         |
         *         ----imooc-locks
         *              |
         *              -----distributed_lock
         */
        try {
            if (client.checkExists().forPath("/" + ZK_LOCK_PROJECT) == null) {
                client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                        .forPath("/" + ZK_LOCK_PROJECT);
            }
            //针对zk的分布式锁节点,创建相应的watcher事件监听
            addWatcherToLock("/" + ZK_LOCK_PROJECT);
        } catch (Exception e) {
            log.error("客户端连接zookeeper服务器错误。。。请重试。。。");
        }
    }

    public void addWatcherToLock(String path) throws Exception {
        final PathChildrenCache cache = new PathChildrenCache(client, path, true);
        cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
        cache.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework curatorFramework, PathChildrenCacheEvent event) throws Exception {
                if (event.getType().equals(PathChildrenCacheEvent.Type.CHILD_REMOVED)) {
                    String path = event.getData().getPath();
                    log.info("上一个会话已释放锁或该会话已断开，节点路径为：" + path);
                    if (path.contains(DISTRIBUTED_LOCK)) {
                        log.info("释放计数器,让当前请求来获得分布式锁...");
                        zkLockLatch.countDown();
                    }
                }
            }
        });
    }

    public void getLock() {
        //使用死循环,当前仅当上一个锁释放并且当前请求获得锁成功后才会跳出
        while (true) {
            try {
                client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)
                        .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                        .forPath("/" + ZK_LOCK_PROJECT + "/" + DISTRIBUTED_LOCK);
                log.info("获得分布式锁成功...");
                return;
            } catch (Exception e) {
                log.error("获得分布式锁失败...");
                try {
                    //如果没有获取到锁，需要重新设置同步资源值
                    if (zkLockLatch.getCount() <= 0) {
                        zkLockLatch = new CountDownLatch(1);
                    }
                    //阻塞线程
                    zkLockLatch.await();
                } catch (Exception e1) {
                    log.error(e1.getMessage());
                }
            }
        }
    }

    /**
     * 释放分布式锁
     *
     * @return
     */
    public boolean releaseLock() {
        try {
            if (client.checkExists().forPath("/" + ZK_LOCK_PROJECT + "/" + DISTRIBUTED_LOCK) != null) {
                client.delete().forPath("/" + ZK_LOCK_PROJECT + "/" + DISTRIBUTED_LOCK);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        log.info("分布式锁释放完毕");
        return true;
    }


}
