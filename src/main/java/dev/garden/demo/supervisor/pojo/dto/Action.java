package dev.garden.demo.supervisor.pojo.dto;

/**
 * 运行中指令的动作。
 *
 * <p>只有会改变平台自身生命周期的操作才需要动作：追加输入、切换任务、停止任务。
 * 用户说的每一句话都是 {@code MESSAGE}，平台不区分它是补充条件还是修改要求，
 * 由模型结合上下文理解；调用方不传动作时按 {@code MESSAGE} 处理。
 */
public enum Action {
    /** 把正文追加为一条新的用户输入，在执行边界交给模型。 */
    MESSAGE,
    /** 结束当前任务并新建替代任务。 */
    SWITCH,
    /** 立即停止当前任务，不要求版本号，也不需要正文。 */
    CANCEL
}
