package ci.fcloud.manager.module;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 环境继承链遍历（§2.2 第 3 步：沿 E 的父链逐级重复，需防环）。
 */
public final class EnvChain {

    private EnvChain() {
    }

    /**
     * 返回从 envId 开始、沿 parent 链向上的环境 id 序列（含自身），防环。
     */
    public static Deque<Long> chainOf(Long envId, java.util.function.Function<Long, Long> parentLookup) {
        Deque<Long> out = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        Long cur = envId;
        while (cur != null && visited.add(cur)) {
            out.add(cur);
            cur = parentLookup.apply(cur);
        }
        return out;
    }
}
