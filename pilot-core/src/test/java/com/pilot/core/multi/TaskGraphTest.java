package com.pilot.core.multi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskGraphTest {

    private static TaskSpec task(String id, String... dependsOn) {
        return TaskSpec.readOnly(id, id, "目标 " + id, List.of(dependsOn), List.of("src/**"));
    }

    @Test
    void rejectsUnknownDependency() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new TaskGraph(List.of(task("T1", "T9"))));

        assertTrue(error.getMessage().contains("T9"));
    }

    @Test
    void rejectsCycles() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new TaskGraph(List.of(task("T1", "T2"), task("T2", "T1"))));

        assertTrue(error.getMessage().contains("环"));
    }

    @Test
    void onlyDependencyFreeTasksAreReadyAtFirst() {
        var graph = new TaskGraph(List.of(task("T1"), task("T2"), task("T3", "T1")));

        List<String> ready = graph.ready(Set.of(), Set.of()).stream().map(TaskSpec::id).toList();

        assertEquals(List.of("T1", "T2"), ready);
    }

    @Test
    void downstreamIsTransitive() {
        var graph = new TaskGraph(List.of(task("T1"), task("T2", "T1"), task("T3", "T2"), task("T4")));

        assertEquals(Set.of("T2", "T3"), graph.downstream("T1"));
    }

    @Test
    void topoOrderPutsDependenciesFirst() {
        var graph = new TaskGraph(List.of(task("T3", "T2"), task("T2", "T1"), task("T1")));

        List<String> order = graph.topoOrder();

        assertTrue(order.indexOf("T1") < order.indexOf("T2"));
        assertTrue(order.indexOf("T2") < order.indexOf("T3"));
    }

    @Test
    void duplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(task("T1"), task("T1"))));
    }
}
