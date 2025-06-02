package com.google.ai.edge.gallery.ui.conversationhistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Conversation
import com.google.ai.edge.gallery.data.DataStoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationHistoryViewModel(private val dataStoreRepository: DataStoreRepository) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    init {
        loadConversations()
    }

    fun loadConversations() {
        viewModelScope.launch {
            _conversations.value = dataStoreRepository.readConversations().sortedByDescending { it.lastModifiedTimestamp }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            dataStoreRepository.deleteConversation(conversationId)
            loadConversations() // Refresh the list
        }
    }
}
