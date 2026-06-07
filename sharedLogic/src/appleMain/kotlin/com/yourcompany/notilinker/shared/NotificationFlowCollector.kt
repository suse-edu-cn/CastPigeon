/*
 * NotificationFlowCollector -- Kotlin Flow 到 Apple 平台的桥接器
 *
 * 问题背景:
 *   Kotlin 的 SharedFlow.collect() 是一个 suspend 函数, 在 Kotlin/Native
 *   编译为 Apple 框架时, suspend 函数无法被 Swift 直接调用 (Swift 的
 *   async/await 与 Kotlin 的 suspend 是不兼容的语言原语)。
 *
 * 解决方案:
 *   本文件在 appleMain 源集中提供一对 start/stop 函数, 内部使用
 *   CoroutineScope 在后台线程中 collect SharedFlow, 每次收到消息时
 *   通过 Swift 兼容的回调闭包 (callback) 将数据传递给 Swift 层。
 *
 * 线程模型:
 *   - collectionJob 运行在 Dispatchers.Default 上 (Kotlin/Native 的共享线程池)
 *   - 回调在 collectionJob 所在的线程中执行 (不保证是主线程)
 *   - Swift 端如需更新 UI, 应在回调内使用 DispatchQueue.main.async { ... }
 *
 * 生命周期:
 *   - startCollectingNotifications: 启动收集, 重复调用会先停止旧任务再新建
 *   - stopCollectingNotifications: 取消当前收集任务
 *
 *   Swift 端应在 App 启动时调用 start, 在 App 终止时调用 stop。
 */
package com.yourcompany.notilinker.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// 持有当前正在运行的收集协程的 Job 引用, 用于后续取消
private var collectionJob: Job? = null

/*
 * 启动通知消息收集。
 *
 * 在后台线程中持续监听 NotificationRepository.messageFlow,
 * 每收到一条 NotificationMessage 即调用一次 callback。
 *
 * 副作用: 若已有正在运行的收集任务, 会先取消旧任务再创建新任务,
 * 保证同一时刻最多只有一个收集任务在运行。
 *
 * @param callback Swift 闭包, 接收 NotificationMessage 参数, 无返回值。
 *                 由于 Kotlin (NotificationMessage) -> Unit 映射到
 *                 ObjC block (void (^)(SharedLogicNotificationMessage *))
 *                 在 Swift 中即为 (NotificationMessage) -> Void。
 */
fun startCollectingNotifications(callback: (NotificationMessage) -> Unit) {
    // 防止重复启动: 先取消已有的收集任务
    stopCollectingNotifications()

    collectionJob = CoroutineScope(Dispatchers.Default).launch {
        NotificationRepository.messageFlow.collect { message ->
            // 每次流中有新消息时, 立即传递给 Swift 端的回调
            callback(message)
        }
    }
}

/*
 * 停止通知消息收集。
 *
 * 取消当前正在运行的收集协程 (如果存在),
 * 并将 collectionJob 引用置空, 使其可被 GC 回收。
 *
 * 该函数是幂等的: 重复调用不会产生副作用。
 */
fun stopCollectingNotifications() {
    collectionJob?.cancel()
    collectionJob = null
}
