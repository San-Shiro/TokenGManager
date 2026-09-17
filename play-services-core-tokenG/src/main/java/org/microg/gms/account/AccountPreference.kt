package org.microg.gms.account

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
// import com.google.android.gms.R
import com.google.android.gms.tokeng.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.listitem.SwipeableListItem

class AccountPreference(context: Context) : Preference(context) {

    var accountAvatar: Drawable? = null
        set(value) {
            field = value; notifyChanged()
        }

    var position: Int = 0
    var itemCount: Int = 0
    var onRemoveListener: (() -> Unit)? = null
    var onSyncListener: (() -> Unit)? = null
    var onCopyTokenListener: (() -> Unit)? = null

    var deviceBadge: String? = null
        set(value) { field = value; notifyChanged() }

    var tokenSnippet: String? = null
        set(value) { field = value; notifyChanged() }

    var syncStatus: String? = null
        set(value) { field = value; notifyChanged() }

    init {
        layoutResource = R.layout.account_item_list
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val layout = holder.itemView as? ListItemLayout ?: return
        val card = holder.findViewById(R.id.account_list_card) as? ListItemCardView
        val avatarView = holder.findViewById(R.id.account_avatar) as? ShapeableImageView
        val nameView = holder.findViewById(R.id.account_name) as? TextView
        val emailView = holder.findViewById(R.id.account_email) as? TextView
        val deviceBadgeView = holder.findViewById(R.id.account_device_badge) as? TextView
        val tokenInfoView = holder.findViewById(R.id.account_token_info) as? TextView
        val syncBadgeView = holder.findViewById(R.id.account_sync_badge) as? TextView
        val actionRemove = holder.findViewById(R.id.account_remove) as? MaterialButton
        val actionSync = holder.findViewById(R.id.account_sync) as? MaterialButton

        layout.updateAppearance(position, itemCount)

        nameView?.text = title
        emailView?.text = summary
        avatarView?.setImageDrawable(accountAvatar)

        if (!deviceBadge.isNullOrEmpty()) {
            deviceBadgeView?.text = deviceBadge
            deviceBadgeView?.visibility = android.view.View.VISIBLE
        } else {
            deviceBadgeView?.visibility = android.view.View.GONE
        }

        if (!tokenSnippet.isNullOrEmpty()) {
            tokenInfoView?.text = tokenSnippet
            tokenInfoView?.visibility = android.view.View.VISIBLE
            tokenInfoView?.setOnClickListener {
                onCopyTokenListener?.invoke()
            }
        } else {
            tokenInfoView?.visibility = android.view.View.GONE
        }

        if (!syncStatus.isNullOrEmpty()) {
            syncBadgeView?.text = syncStatus
            syncBadgeView?.visibility = android.view.View.VISIBLE
            when {
                syncStatus?.startsWith("✓") == true -> {
                    syncBadgeView?.setTextColor(android.graphics.Color.parseColor("#10B981"))
                }
                syncStatus?.startsWith("⏳") == true -> {
                    syncBadgeView?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                }
                syncStatus?.startsWith("⚠") == true -> {
                    syncBadgeView?.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                }
            }
        } else {
            syncBadgeView?.visibility = android.view.View.GONE
        }

        card?.setOnClickListener {
            layout.swipeState = if (layout.swipeState == SwipeableListItem.STATE_CLOSED) {
                SwipeableListItem.STATE_OPEN
            } else {
                SwipeableListItem.STATE_CLOSED
            }
        }

        actionSync?.setOnClickListener {
            onSyncListener?.invoke()
            layout.swipeState = SwipeableListItem.STATE_CLOSED
        }

        actionRemove?.setOnClickListener {
            onRemoveListener?.invoke()
            layout.swipeState = SwipeableListItem.STATE_CLOSED
        }
    }
}