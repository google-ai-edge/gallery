package com.google.ai.edge.gallery.ui.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class PersonaViewModel(private val dataStoreRepository: DataStoreRepository) : ViewModel() {

    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    val activePersonaId: StateFlow<String?> = dataStoreRepository.readActivePersonaId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        loadPersonas()
    }

    fun loadPersonas() {
        viewModelScope.launch {
            _personas.value = dataStoreRepository.readPersonas()
        }
    }

    fun addPersona(name: String, prompt: String) {
        viewModelScope.launch {
            val newPersona = Persona(
                id = UUID.randomUUID().toString(),
                name = name,
                prompt = prompt
            )
            dataStoreRepository.addPersona(newPersona)
            loadPersonas() // Refresh list
        }
    }

    fun updatePersona(persona: Persona) {
        viewModelScope.launch {
            dataStoreRepository.updatePersona(persona)
            loadPersonas() // Refresh list
        }
    }

    fun deletePersona(personaId: String) {
        viewModelScope.launch {
            dataStoreRepository.deletePersona(personaId)
            // If the deleted persona was active, clear the active state
            if (activePersonaId.value == personaId) {
                setActivePersona(null)
            }
            loadPersonas() // Refresh list
        }
    }

    fun setActivePersona(personaId: String?) {
        viewModelScope.launch {
            dataStoreRepository.saveActivePersonaId(personaId)
        }
    }
}
