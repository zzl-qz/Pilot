package com.pilot.core.coordination;

/**
 * 协调状态的持久化端口：只负责把一份不可变快照落盘并读回。
 *
 * <p>实现方不能据此伪造在线状态——在线状态不进快照。
 */
public interface CoordinationSnapshotStore {

    /** 读取上次保存的状态；没有存档时返回空快照。 */
    CoordinationSnapshot load();

    /** 原子替换整份状态；失败必须抛出，不能静默丢状态。 */
    void save(CoordinationSnapshot snapshot);

    /** 内存实现：测试与不需要持久化的装配用。 */
    final class InMemory implements CoordinationSnapshotStore {
        private CoordinationSnapshot snapshot = CoordinationSnapshot.empty();

        @Override
        public synchronized CoordinationSnapshot load() {
            return snapshot;
        }

        @Override
        public synchronized void save(CoordinationSnapshot next) {
            this.snapshot = next == null ? CoordinationSnapshot.empty() : next;
        }
    }
}
