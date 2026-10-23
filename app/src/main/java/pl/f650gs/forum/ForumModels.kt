package pl.f650gs.forum

data class ForumCategory(val name: String, val forums: List<ForumSummary>)

data class ForumSummary(
    val title: String,
    val description: String,
    val postCount: String,
    val latestTopic: String,
    val latestAuthor: String,
    val latestTime: String,
    val url: String,
    val markReadUrl: String = "",
)

data class ActivityStory(
    val title: String,
    val author: String,
    val context: String,
    val snippet: String,
    val time: String,
    val replies: String,
    val url: String,
    val photo: String = "",
    val authorUrl: String = "",
)

data class TopicSummary(
    val title: String,
    val author: String,
    val date: String,
    val replies: String,
    val url: String,
    val isUnread: Boolean = false,
)

data class ForumPage(
    val title: String,
    val topics: List<TopicSummary>,
    val markReadUrl: String = "",
    val subforums: List<ForumSummary> = emptyList(),
    val sorts: List<SortOption> = emptyList(),
    val pager: Pager = Pager(),
    /** The forum offers you its "new topic" button here. */
    val canPost: Boolean = false,
)

/** "Strona X z N" and page links; an empty link means that button is greyed out. */
data class Pager(
    val label: String = "",
    val firstUrl: String = "",
    val prevUrl: String = "",
    val nextUrl: String = "",
    val lastUrl: String = "",
    /** Running number of the first item on this page (1 on page 1). */
    val firstNumber: Int = 1,
)

/** One entry of a section's "Sortuj wg" menu. */
data class SortOption(val label: String, val url: String, val selected: Boolean)

data class ForumPost(
    val id: Long = 0,
    val author: String,
    val date: String,
    val text: String,
    val html: String,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val likeUrl: String = "",
    val unlikeUrl: String = "",
    val likedBy: String = "",
    val photo: String = "",
    val editUrl: String = "",
    val authorUrl: String = "",
)

/** An edit form for an existing post; [originalHtml] is what the editor would start with. */
data class EditForm(val form: ReplyForm, val originalHtml: String)

/** Header badge counts plus the number of unread topics. */
data class Counters(val inbox: Int, val notifications: Int, val unreadTopics: Int)

data class ChatMessage(
    val id: Long,
    val memberId: String,
    val author: String,
    val photoUrl: String,
    val text: String,
    val timestamp: Long,
)

data class ChatBatch(val messages: List<ChatMessage>, val lastId: Long)

data class TopicPage(
    val title: String,
    val forum: String,
    val posts: List<ForumPost>,
    val url: String,
    val focusPostId: Long = 0,
    val pager: Pager = Pager(),
    val replyForm: ReplyForm? = null,
)

/** A forum comment form (topic reply or conversation reply): where to post it and its hidden fields. */
data class ReplyForm(val action: String, val fields: Map<String, String>, val editor: String) {
    val contentId: String get() = fields.keys.firstNotNullOfOrNull { Regex("""commentform_(\d+)_submitted""").find(it)?.groupValues?.get(1) }.orEmpty()
}

data class Conversation(
    val title: String,
    val url: String,
    val snippet: String,
    val participants: String,
    val count: String,
    val time: String,
    val unread: Boolean,
    val photo: String = "",
    /** Whose photo [photo] is (the conversation starter). */
    val photoOwner: String = "",
)

/** The signed-in member as the forum's user menu shows them; [links] are the menu entries except logout. */
data class Account(
    val name: String,
    val profileUrl: String,
    val photo: String,
    val links: List<Pair<String, String>>,
    val logoutUrl: String,
)

/** A member's public profile page. [stats] and [fields] are label to value pairs in page order. */
data class MemberProfile(
    val id: String,
    val name: String,
    val url: String,
    val photo: String,
    val group: String,
    val online: Boolean,
    val stats: List<Pair<String, String>>,
    val fields: List<Pair<String, String>>,
    val activity: List<ActivityStory>,
    val canMessage: Boolean,
)

/** A member as the forum's name search returns them. */
data class MemberHit(val id: String, val name: String, val photo: String)

/** The "new conversation" form, already addressed to [recipient]. */
data class ComposeForm(val form: ReplyForm, val recipient: String)

/** A section's "new topic" form; the topic title goes in [titleField]. */
data class NewTopicForm(val form: ReplyForm, val titleField: String)

data class PrivateMessage(val authorId: String, val author: String, val date: String, val html: String, val photo: String = "")

data class ConversationPage(
    val title: String,
    val url: String,
    val messages: List<PrivateMessage>,
    val myMemberId: String,
    val replyForm: ReplyForm?,
    val olderUrl: String,
)

/** One entry of the member's notification list; [url] usually points at a post in a topic. */
data class ForumNotification(
    val text: String,
    val url: String,
    val time: String,
    val author: String,
    val photo: String,
    val unread: Boolean,
)

/** A page of notifications; [nextUrl] is blank on the last page. */
data class NotificationsPage(val items: List<ForumNotification>, val nextUrl: String)

/**
 * One field of the forum's sign-up form, as the forum describes it. [kind] is text, email,
 * password, number, select, textarea or checkbox; a checkbox's [value] is "1" when ticked.
 */
data class SignUpField(
    val name: String,
    val label: String,
    val kind: String,
    val required: Boolean,
    val description: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val value: String = "",
    val error: String = "",
    val maxLength: Int = 0,
    val link: String = "",
    /** A member-name box: the app suggests forum members as you type. */
    val memberSearch: Boolean = false,
)

/** The sign-up form with its hidden fields; [cookies] tie its csrfKey to the guest session. */
data class SignUpForm(
    val action: String,
    val hidden: Map<String, String>,
    val fields: List<SignUpField>,
    val error: String = "",
    val cookies: Map<String, String> = emptyMap(),
)

sealed class SignUpResult {
    /** The forum sent the form back, with its messages next to the fields. */
    data class Retry(val form: SignUpForm) : SignUpResult()
    /** The account exists; [signedIn] when the forum also signed it in with [cookies]. */
    data class Done(val message: String, val cookies: Map<String, String>, val signedIn: Boolean) : SignUpResult()
}

/** One switch of a forum settings form: a checkbox, or one choice of the radio group named [name]. */
data class FormToggle(
    val name: String,
    val value: String,
    val label: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val radio: Boolean = false,
)

data class FormSettingRow(val title: String, val description: String, val toggles: List<FormToggle>)

data class FormSettingSection(val title: String, val rows: List<FormSettingRow>)

/** The forum's notification settings: groups of switches plus the hidden fields sent back with them. */
data class SettingsForm(val action: String, val hidden: Map<String, String>, val sections: List<FormSettingSection>)

/** Any forum form, field by field, in page order; [cookies] carry a guest session's csrfKey. */
data class ForumForm(
    val action: String,
    val hidden: Map<String, String>,
    val items: List<FormItem>,
    val submit: String,
    val cookies: Map<String, String> = emptyMap(),
)

sealed class FormItem {
    data class Input(val field: SignUpField) : FormItem()
    /** Boxes or radio choices under [title]; a matrix row has one labelled toggle per column. */
    data class Choices(val title: String, val rows: List<FormSettingRow>) : FormItem()
    /** A value shown but not editable here ("Bieżący adres e-mail"). */
    data class Info(val label: String, val value: String) : FormItem()
}

/** A row of a list on a forum page; [actions] are one-tap forum actions (label to address). */
data class PageLink(
    val title: String,
    val url: String,
    val detail: String = "",
    val photo: String = "",
    val actions: List<Pair<String, String>> = emptyList(),
)

sealed class PageBlock {
    data class Heading(val text: String) : PageBlock()
    data class Text(val text: String) : PageBlock()
    data class Links(val items: List<PageLink>) : PageBlock()
    data class Form(val form: ForumForm) : PageBlock()
}

/** A forum page drawn natively; [message] and [error] are the forum's own notices on it. */
data class NativePage(
    val title: String,
    val url: String,
    val blocks: List<PageBlock>,
    val message: String = "",
    val error: String = "",
)

/** What the forum answered to a form: its next page (if drawable), whether it took the form, new cookies. */
data class FormOutcome(val page: NativePage?, val saved: Boolean, val cookies: Map<String, String>)

