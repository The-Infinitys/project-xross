package org.xross

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

class XrossPluginTest {
    @Test fun `plugin registers xross tasks`() {
        // プロジェクトの作成
        val project = ProjectBuilder.builder().build()

        // プラグインの適用 (IDは build.gradle.kts で設定したもの)
        project.plugins.apply("org.xross")

        // 登録したタスクが存在するか確認
        assertNotNull(project.tasks.findByName("generateXrossBindings"))
        assertNotNull(project.tasks.findByName("buildXrossNatives"))
    }
}
