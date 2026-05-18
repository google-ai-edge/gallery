package com.smartscreenshot.organizer.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartscreenshot.organizer.data.model.Category
import com.smartscreenshot.organizer.ui.theme.*

@Composable
fun CategoryChip(
    category: Category,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val chipColor = getCategoryColor(category)
    val chipIcon = getCategoryIcon(category)

    if (onClick != null) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    text = category.displayName,
                    style = if (compact) MaterialTheme.typography.labelSmall
                    else MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = chipIcon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 14.dp else 18.dp)
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = chipColor.copy(alpha = 0.12f),
                labelColor = chipColor,
                leadingIconContentColor = chipColor
            ),
            modifier = modifier
        )
    } else {
        // Non-clickable version using same visual style
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    text = category.displayName,
                    style = if (compact) MaterialTheme.typography.labelSmall
                    else MaterialTheme.typography.labelMedium,
                    color = chipColor
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = chipIcon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 14.dp else 18.dp),
                    tint = chipColor
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = chipColor.copy(alpha = 0.12f),
                disabledLabelColor = chipColor,
                disabledLeadingIconContentColor = chipColor
            ),
            border = null,
            modifier = modifier
        )
    }
}

fun getCategoryColor(category: Category): Color = when (category) {
    Category.SHOPPING -> CategoryShopping
    Category.RECEIPTS -> CategoryReceipts
    Category.CHAT -> CategoryChat
    Category.SOCIAL_MEDIA -> CategorySocialMedia
    Category.BANKING -> CategoryBanking
    Category.TRAVEL -> CategoryTravel
    Category.WORK -> CategoryWork
    Category.CODING -> CategoryCoding
    Category.MEMES -> CategoryMemes
    Category.DOCUMENTS -> CategoryDocuments
    Category.OTHER -> CategoryOther
}

fun getCategoryIcon(category: Category): ImageVector = when (category) {
    Category.SHOPPING -> Icons.Default.ShoppingCart
    Category.RECEIPTS -> Icons.Default.Receipt
    Category.CHAT -> Icons.Default.Chat
    Category.SOCIAL_MEDIA -> Icons.Default.Share
    Category.BANKING -> Icons.Default.AccountBalance
    Category.TRAVEL -> Icons.Default.Flight
    Category.WORK -> Icons.Default.Work
    Category.CODING -> Icons.Default.Code
    Category.MEMES -> Icons.Default.EmojiEmotions
    Category.DOCUMENTS -> Icons.Default.Description
    Category.OTHER -> Icons.Default.MoreHoriz
}
