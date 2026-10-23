package pl.f650gs.forum

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

internal sealed class Screen {
    data object Home : Screen()
    data object Forums : Screen()
    data object Saved : Screen()
    data object Unread : Screen()
    data object Chatbox : Screen()
    data object Messages : Screen()
    data class Search(val query: String = "") : Screen()
    data class Forum(val title: String, val url: String) : Screen()
    data class Topic(val title: String, val url: String) : Screen()
    data class Conversation(val title: String, val url: String) : Screen()
    data class Profile(val name: String, val url: String) : Screen()
    /** New private message; a blank [memberId] means the recipient is picked on the screen. */
    data class Compose(val memberId: String = "", val name: String = "", val photo: String = "") : Screen()
    /** New topic in the section at [url]. */
    data class NewTopic(val forum: String, val url: String) : Screen()
    data object Settings : Screen()
    data object Login : Screen()
    data object Notifications : Screen()
    data object SignUp : Screen()
    data object NotificationSettings : Screen()
    data object Account : Screen()
    /** Any other forum page, drawn from its forms and lists; falls back to the web page. */
    data class ForumPage(val title: String, val url: String) : Screen()
}

internal enum class Tab { HOME, FORUMS, UNREAD, CHATBOX, MESSAGES, SAVED }
