package androidx.compose.material3

import androidx.compose.ui.Modifier

enum class ExposedDropdownMenuAnchorType {
  PrimaryEditable,
  PrimaryNotEditable,
  SecondaryEditable
}

fun Modifier.menuAnchor(type: ExposedDropdownMenuAnchorType): Modifier = this
