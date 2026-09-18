package com.chinaex123.fox_trot_brew.compat;

import com.chinaex123.fox_trot_brew.maid.task.TaskPressingTub;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

/**
 * 车万女仆模组兼容入口。
 * 在模组加载时向女仆任务管理器注册自定义果盆任务。
 */
@LittleMaidExtension
public class LittleMaidCompat implements ILittleMaid {

    /**
     * 向女仆任务管理器注册自定义任务
     * <p>
     * 此方法在模组加载时被调用，用于将自定义的女仆任务添加到任务系统中，
     * 使女仆能够执行特定的交互行为。
     *
     * @param manager 女仆任务管理器实例，用于注册和管理所有可用的女仆任务
     */
    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new TaskPressingTub());
    }
}
