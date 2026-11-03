【执行段即将结束，不代表任务已经完成】

目标：{{objective}}
已满足条件及证据：
{{completedConditions}}
已改文件：
{{changedFiles}}
验证记录：
{{verification}}
未完成缺口：
{{remaining}}
恢复后的唯一下一动作：{{nextAction}}

停止宽泛搜索、无关重构和新增范围。如果只差一次明确且相关的验证，可以执行该验证；否则立即保存真实 checkpoint。不得把预算耗尽描述为完成，也不得清空未完成计划。

最终记录必须区分 COMPLETE、PARTIAL、WAITING 与 NEED_INPUT，并保留证据 ID、验证类型、缺口和恢复动作。
