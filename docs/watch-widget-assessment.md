# Watch S4 系统小组件可行性

核对日期：2026-10-26，目标为国行 S4 3.201.79。这里的小组件特指从表盘旋转表冠进入的系统卡片列表，不是手机桌面 Widget，也不是应用内自定义组件。

小米官方 [S4 小组件说明](https://www.mi.com/ph/support/faq/details/KA-538734/)确认手表上可编辑、添加系统提供的小组件。这证明系统有该功能，没有说明第三方 RPK 如何注册进去；该 FAQ 属海外机型资料，国行细节还需分别验证。

本次查阅了 Vela [项目配置](https://iot.mi.com/vela/quickapp/zh/guide/framework/manifest.html)、[组件目录](https://iot.mi.com/vela/quickapp/zh/components/)、[APILevel3](https://iot.mi.com/vela/quickapp/zh/guide/version/APILevel3.html) 与 [APILevel4](https://iot.mi.com/vela/quickapp/zh/guide/version/APILevel4.html)，未找到面向 S4 第三方应用的系统卡片注册、入口声明、卡片生命周期或分发规范。

因此，当前公开资料不足以交付“安装后可加入系统小组件菜单”的版本。没有添加猜测的 widget manifest 字段，也没有把普通应用内页面当作系统小组件。这个结论是公开接入证据不足，不等于硬件绝对不支持或小米合作方没有私有接入方式。

Vela 的 [hap 链接](https://iot.mi.com/vela/quickapp/zh/guide/framework/other/hap-schema.html)可打开应用，但只有其他宿主提供可配置链接入口时才有用；链接本身不会创建系统卡片。快捷键能否选择第三方 Action 遥控，也需要在目标固件的候选列表实测，不能当作已实现的替代功能。

要继续实现，需要取得小米针对 S4 的第三方卡片开发/分发规范或可验证的官方示例。现阶段保留现有应用启动方式；不刷固件，不替换系统组件。
