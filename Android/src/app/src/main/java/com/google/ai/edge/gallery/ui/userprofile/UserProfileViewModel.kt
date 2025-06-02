package com.google.ai.edge.gallery.ui.userprofile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class UserProfileViewModel(private val dataStoreRepository: DataStoreRepository) : ViewModel() {

    private val _userProfile = MutableStateFlow(UserProfile()) // Initialize with default
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    init {
        loadUserProfile()
    }

    fun loadUserProfile() {
        viewModelScope.launch {
            _userProfile.value = dataStoreRepository.readUserProfile() ?: UserProfile()
        }
    }

    fun saveUserProfile() {
        viewModelScope.launch {
            dataStoreRepository.saveUserProfile(_userProfile.value)
        }
    }

    fun updateName(name: String) {
        _userProfile.value = _userProfile.value.copy(name = name)
    }

    fun updateSummary(summary: String) {
        _userProfile.value = _userProfile.value.copy(summary = summary)
    }

    // Skills management
    fun addSkill(skill: String = "") { // Add empty skill by default
        val currentSkills = _userProfile.value.skills.toMutableList()
        currentSkills.add(skill)
        _userProfile.value = _userProfile.value.copy(skills = currentSkills)
    }

    fun updateSkill(index: Int, skill: String) {
        val currentSkills = _userProfile.value.skills.toMutableList()
        if (index >= 0 && index < currentSkills.size) {
            currentSkills[index] = skill
            _userProfile.value = _userProfile.value.copy(skills = currentSkills)
        }
    }

    fun removeSkill(index: Int) {
        val currentSkills = _userProfile.value.skills.toMutableList()
        if (index >= 0 && index < currentSkills.size) {
            currentSkills.removeAt(index)
            _userProfile.value = _userProfile.value.copy(skills = currentSkills)
        }
    }

    // Experience management
    fun addExperience(experienceItem: String = "") { // Add empty experience by default
        val currentExperience = _userProfile.value.experience.toMutableList()
        currentExperience.add(experienceItem)
        _userProfile.value = _userProfile.value.copy(experience = currentExperience)
    }

    fun updateExperience(index: Int, experienceItem: String) {
        val currentExperience = _userProfile.value.experience.toMutableList()
        if (index >= 0 && index < currentExperience.size) {
            currentExperience[index] = experienceItem
            _userProfile.value = _userProfile.value.copy(experience = currentExperience)
        }
    }

    fun removeExperience(index: Int) {
        val currentExperience = _userProfile.value.experience.toMutableList()
        if (index >= 0 && index < currentExperience.size) {
            currentExperience.removeAt(index)
            _userProfile.value = _userProfile.value.copy(experience = currentExperience)
        }
    }
}
