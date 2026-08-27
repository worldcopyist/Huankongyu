package com.huankongyu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelTest {
    @Test
    fun creatingCharacterStartsAnIndependentConversation() {
        val viewModel = AppViewModel()

        viewModel.createCharacter("初夏", "旅行搭档", "开朗")

        assertEquals("初夏", viewModel.selectedCharacter().name)
        assertTrue(viewModel.messagesFor(viewModel.selectedCharacterId).single().content.contains("初夏"))
    }

    @Test
    fun sendingMessageAddsUserAndLocalModeReply() {
        val viewModel = AppViewModel()

        viewModel.sendMessage("你好")

        val messages = viewModel.messagesFor("lan")
        assertEquals(3, messages.size)
        assertTrue(messages.last().content.contains("本地体验模式"))
    }
}
