package android.view

import android.content.Context
import javax.swing.JComponent
import javax.swing.JPanel

interface ViewParent {
  fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean)
}

open class View(open val context: Context = Context.INSTANCE) {
  var layoutParams: ViewGroup.LayoutParams? = null
  open val component: JComponent = JPanel()
  
  val parent: ViewParent = object : ViewParent {
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
  }

  fun announceForAccessibility(text: CharSequence) {}
}
