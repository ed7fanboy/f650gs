package pl.f650gs.forum

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/** Uses the forum's own login and editor pages for authenticated actions. */
class ForumWebActivity : Activity() {
    companion object {
        const val EXTRA_URL = "forum_url"
        const val EXTRA_LOGIN_FLOW = "login_flow"
        const val EXTRA_SIGNED_IN = "signed_in"
        const val EXTRA_DARK_MODE = "dark_mode"
        const val EXTRA_TITLE = "screen_title"
        private const val FORUM_HOST = "f650gs.pl"
        private val TEAL = Color.rgb(0, 102, 177)
        private const val LIGHT_VARIABLES_CSS = """
            :root{--f650-bg:#f1f5f8;--f650-surface:#fff;--f650-panel:#eaf3fb;--f650-input:#fff;--f650-text:#1b2b30;--f650-muted:#586c71;--f650-border:#dbe4e8;--f650-input-border:#cbd7df;--f650-link:#0066b1;--f650-author:#0066b1;--f650-quote-text:#263b3e;--f650-quote-border:#367eae;--f650-quote-citation:#245d82;--f650-button:#0066b1;--f650-button-text:#fff;--f650-switch-off:#8d9b99;--f650-switch-on:#2f8175}
        """
        private const val DARK_VARIABLES_CSS = """
            :root{color-scheme:dark;--f650-bg:#11191c;--f650-surface:#1d292d;--f650-panel:#26383b;--f650-input:#202d31;--f650-text:#e8efed;--f650-muted:#b9c8c9;--f650-border:#36484b;--f650-input-border:#516367;--f650-link:#a6cae0;--f650-author:#c3e5ef;--f650-quote-text:#e8efed;--f650-quote-border:#8abbb5;--f650-quote-citation:#b8d4d0;--f650-button:#0f272b;--f650-button-text:#fff;--f650-switch-off:#718180;--f650-switch-on:#3c9a8b}
        """
        private const val DARK_INLINE_BACKGROUND_CSS = """
            [style*="background:#fff"],[style*="background: #fff"],[style*="background:#ffffff"],[style*="background: #ffffff"],[style*="background-color:#fff"],[style*="background-color: #fff"],[style*="background-color:#ffffff"],[style*="background-color: #ffffff"],[style*="rgb(255, 255, 255)"],[style*="rgb(255,255,255)"],[style*="background:white"],[style*="background: white"],[style*="background-color:white"],[style*="background-color: white"],[bgcolor="#fff"],[bgcolor="#ffffff"],[bgcolor="white"]{background-color:var(--f650-surface)!important;color:var(--f650-text)!important;border-color:var(--f650-border)!important}
        """
        private const val BASE_PAGE_CSS = """
            html,body,#ipsLayout_body,#ipsLayout_mainArea,#ipsLayout_contentWrapper{background:var(--f650-bg)!important;background-image:none!important;color:var(--f650-text)!important}
            #ipsLayout_header,#ipsLayout_footer,#elMobileNav{display:none!important}
            .ipsBox_alt,.ipsBox,.ipsAreaBackground,.ipsAreaBackground_light,.ipsAreaBackground_reset,.ipsComment,.ipsDataItem,.ipsDataList,.ipsStreamItem,.ipsStreamItem_container,.ipsWidget_inner,.ipsTable,.ipsTabs,.ipsSideMenu,.ipsFieldRow,.ipsPad,.ipsPad_half,.ipsComposeArea,.ipsComposeArea_editor,.ipsFollow,.ipsNotification{background-color:var(--f650-surface)!important;color:var(--f650-text)!important;border-color:var(--f650-border)!important}
            .ipsBox_alt,.ipsBox,.ipsAreaBackground_light,.ipsComment,.ipsDataItem,.ipsStreamItem,.ipsWidget_inner,.ipsTable,.ipsTabs,.ipsSideMenu,.ipsComposeArea,.ipsComposeArea_editor,.ipsFollow,.ipsNotification{border-radius:16px!important;box-shadow:none!important}
            .ipsForm,.ipsFieldRow_content,.ipsFieldRow_desc,.ipsToggle,.ipsDataItem_main,.ipsDataItem_generic,.ipsDataItem_lastPoster,.ipsTabs_item,.ipsTabs_content,.ipsTable_zebra tr,.ipsTable_zebra td,.ipsTable_zebra th{background-color:var(--f650-surface)!important;color:var(--f650-text)!important;border-color:var(--f650-border)!important}
            .ipsType_light,.ipsType_small{color:var(--f650-muted)!important}
            .ipsQuote,blockquote.ipsQuote{background:var(--f650-panel)!important;color:var(--f650-quote-text)!important;border-left:4px solid var(--f650-quote-border)!important;border-radius:8px;padding:10px 12px}
            .ipsQuote_citation{color:var(--f650-quote-citation)!important;font-weight:700}
            .ipsComment_header,.cAuthorPane,.cAuthorPane_info{background:var(--f650-panel)!important;color:var(--f650-text)!important}
            .ipsComment_author,.ipsComment_author a,.cAuthorPane_author,.cAuthorPane_author a,.ipsComment_author a:visited{color:var(--f650-author)!important;font-weight:800!important}
            .ipsComment_content,.ipsComment_content .ipsType_richText,.ipsComment_content p,.ipsComment_content li{color:var(--f650-text)!important}
            a{color:var(--f650-link)!important}
            .ipsButton_light,.ipsButton_veryLight,.ipsButton_link{background:var(--f650-panel)!important;color:var(--f650-link)!important;border-color:var(--f650-border)!important}
            ul[data-controller="core.front.core.sharelink"],a.cShareLink{display:none!important}
            input[type=text],input[type=password],input[type=email],input[type=search],textarea,select{box-sizing:border-box!important;background:var(--f650-input)!important;color:var(--f650-text)!important;border:1px solid var(--f650-input-border)!important;font-size:16px!important;max-width:100%!important;min-height:48px}
            input[type=checkbox]:not(.f650gsNotifyRow input),input[type=radio]{-webkit-appearance:none!important;appearance:none!important;box-sizing:border-box!important;width:21px!important;height:21px!important;min-height:21px!important;margin:0 8px 0 0!important;vertical-align:middle!important;background:var(--f650-input)!important;border:1px solid var(--f650-input-border)!important;border-radius:5px!important;accent-color:var(--f650-link)!important}
            input[type=checkbox]:not(.f650gsNotifyRow input):checked{background-color:var(--f650-link)!important;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='m3 8 3 3 7-7' fill='none' stroke='white' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E")!important;background-size:16px!important;background-position:center!important;background-repeat:no-repeat!important;border-color:var(--f650-link)!important}
            input[type=radio]:checked{box-shadow:inset 0 0 0 5px var(--f650-input)!important;background:var(--f650-link)!important;border-color:var(--f650-link)!important}
            button[type=submit],.ipsButton_primary{background:var(--f650-button)!important;border:0!important;border-radius:12px!important;color:var(--f650-button-text)!important;min-height:48px;font-size:16px!important}
            .ipsType_veryLarge{font-size:24px!important;color:var(--f650-text)!important}
        """
        private const val HIDE_SCROLLBARS_CSS = """
            html,body,*{scrollbar-width:none!important;-ms-overflow-style:none!important}
            *::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}
        """
        private const val LOGIN_CSS = """
            form[data-controller="core.global.core.login"]{max-width:680px!important;margin:8px auto!important;padding:0!important;background:transparent!important;border:0!important;box-shadow:none!important}
            form[data-controller="core.global.core.login"] h1.ipsType_veryLarge{display:none!important}
            form[data-controller="core.global.core.login"]>.ipsSpacer_both{margin:0 auto 12px!important}
            form[data-controller="core.global.core.login"]>.ipsSpacer_both p{font-size:15px!important;line-height:1.45!important;text-align:center!important;margin:4px!important;color:var(--f650-muted)!important}
            form[data-controller="core.global.core.login"]>.ipsSpacer_both a{font-weight:700!important}
            .cLogin_single{max-width:680px!important;margin:8px auto!important;padding:0!important}
            .cLogin_single .ipsBox,.cLogin_single .ipsBox_alt,.cLogin_single .ipsAreaBackground_light{background:var(--f650-surface)!important;color:var(--f650-text)!important;border:1px solid var(--f650-border)!important;border-radius:18px!important;padding:16px!important}
            .cLogin_single .ipsFieldRow_title,.cLogin_single label{color:var(--f650-text)!important;font-size:16px!important;font-weight:700!important}
            .cLogin_single .ipsFieldRow_required{font-size:11px!important;font-weight:600!important}
            .cLogin_single input[type=text],.cLogin_single input[type=password],.cLogin_single input[type=email]{width:100%!important;min-height:48px!important;padding:10px 14px!important;background:var(--f650-input)!important;color:var(--f650-text)!important;border:1px solid var(--f650-input-border)!important;border-radius:12px!important;font-size:16px!important}
            .cLogin_single .ipsFieldRow{margin-bottom:12px!important}
            .cLogin_single button[type=submit],.cLogin_single .ipsButton_primary{width:100%!important;margin-top:4px!important}
            .cLogin_single a{font-weight:600!important}
        """
        private const val NATIVE_PAGE_CSS = """
            html,body{font-family:Roboto,'Arial',sans-serif!important}
            #ipsLayout_body,#ipsLayout_contentWrapper,#ipsLayout_contentArea,#ipsLayout_mainArea{box-sizing:border-box!important;width:100%!important;max-width:100%!important;margin:0 auto!important}
            .ipsBreadcrumb{display:none!important}
            #ipsLayout_contentWrapper{padding:12px!important}
            .ipsPageHeader{margin:0 0 14px!important;padding:4px 2px!important}
            .ipsType_pageTitle{font-size:22px!important;line-height:1.25!important}
            .ipsBox,.ipsBox_alt,.ipsAreaBackground_light,.ipsDataItem,.ipsMessage,.ipsComposeArea,.ipsPad{border-radius:14px!important;box-shadow:none!important}
            .ipsTabs{overflow-x:auto!important;white-space:nowrap!important}
            .ipsTabs_item{min-height:44px!important}
            .ipsButton,.ipsButton_primary,.ipsButton_important,button[type=submit]{min-height:46px!important;border-radius:12px!important;padding:10px 16px!important;font-weight:700!important}
            input[type=text],input[type=password],input[type=email],input[type=search],textarea,select{box-sizing:border-box!important;max-width:100%!important;min-height:48px!important;font-size:16px!important;border-radius:10px!important}
            .ipsForm_horizontal .ipsFieldRow,.ipsFieldRow{margin:0 0 14px!important}
            .ipsComposeArea,.ipsComposeArea_editor,.cke,.cke_inner,.cke_top,.cke_bottom,.cke_contents{border-radius:10px!important}
            .ipsComposeArea{overflow:hidden!important}
        """
        private const val COMPOSER_CSS = """
            .f650gsComposer{box-sizing:border-box!important;width:92%!important;max-width:720px!important;margin:12px auto!important;padding:12px!important;background:var(--f650-surface)!important;color:var(--f650-text)!important;border:1px solid var(--f650-border)!important;border-radius:16px!important}
            .f650gsComposer,.f650gsComposer .ipsBox,.f650gsComposer .ipsBox_alt,.f650gsComposer .ipsAreaBackground_light,.f650gsComposer .ipsAreaBackground_reset,.f650gsComposer .ipsFieldRow,.f650gsComposer .ipsFieldRow_content,.f650gsComposer .ipsFieldRow_desc,.f650gsComposer .ipsPad,.f650gsComposer .ipsPad_half,.f650gsComposer .ipsComposeArea{background:var(--f650-surface)!important;color:var(--f650-text)!important;border-color:var(--f650-border)!important}
            .f650gsComposer .ipsComposeArea,.f650gsComposer .ipsComposeArea_editor,.f650gsComposer .cke,.f650gsComposer .cke_inner,.f650gsComposer .cke_contents{box-sizing:border-box!important;width:100%!important;max-width:100%!important}
            .f650gsComposer .ipsComposeArea_attachments,.f650gsComposer .ipsComposeArea_dropZone,.f650gsComposer .ipsComposeArea_dropzone,.f650gsComposer .ipsComposeArea_attachmentList{display:none!important}
            .f650gsComposer .cke,.f650gsComposer .cke_inner{border:0!important;background:var(--f650-surface)!important}
            .f650gsComposer .cke_top,.f650gsComposer .cke_bottom,.f650gsComposer .cke_toolbar,.f650gsComposer .cke_toolgroup,.f650gsComposer .cke_button,.f650gsComposer .cke_combo_button{background:var(--f650-panel)!important;border-color:var(--f650-border)!important}
            .f650gsComposer .cke_contents,.f650gsComposer .cke_wysiwyg_frame,.f650gsComposer .cke_wysiwyg_div{background:var(--f650-input)!important;color:var(--f650-text)!important}
            .f650gsComposer .f650gsNotifyRow{display:flex!important;align-items:center!important;gap:12px!important;box-sizing:border-box!important;padding:10px 12px!important;margin:12px 0!important;background:var(--f650-panel)!important;color:var(--f650-text)!important;border:1px solid var(--f650-border)!important;border-radius:14px!important}
            .f650gsComposer .f650gsNotifyRow label,.f650gsComposer .f650gsNotifyRow span{color:var(--f650-text)!important;font-size:15px!important;line-height:1.4!important;font-weight:600!important}
            .f650gsComposer .f650gsNotifyRow input[type=checkbox]{-webkit-appearance:none!important;appearance:none!important;flex:0 0 54px!important;box-sizing:border-box!important;width:54px!important;height:32px!important;min-height:32px!important;padding:0!important;margin:0!important;border:0!important;border-radius:999px!important;background-color:var(--f650-switch-off)!important;background-image:radial-gradient(circle at 16px 16px,#fff 0 11px,transparent 12px)!important;transition:background-color .16s ease,background-image .16s ease!important}
            .f650gsComposer .f650gsNotifyRow input[type=checkbox]:checked{background-color:var(--f650-switch-on)!important;background-image:radial-gradient(circle at 38px 16px,#fff 0 11px,transparent 12px)!important}
            .f650gsComposer .f650gsNotifyRow input[type=checkbox]:focus-visible{outline:3px solid var(--f650-link)!important;outline-offset:2px!important}
        """
        private const val HIDE_SIDE_NAV_CSS = """
            #ipsLayout_sideBar,.ipsLayout_sidebar,.ipsLayout_sidebarRight{display:none!important}
        """
        private const val MESSAGE_NAV_CSS = """
            #ipsLayout_sideBar,.ipsLayout_sidebar,.ipsLayout_sidebarRight{display:block!important;box-sizing:border-box!important;float:none!important;position:static!important;width:100%!important;max-width:100%!important;margin:0 0 12px!important}
            #ipsLayout_sideBar .ipsSideMenu,#ipsLayout_sideBar .cInboxNavigation,#ipsLayout_sideBar #elMessengerNav,#ipsLayout_sideBar [data-role="inboxNavigation"]{display:flex!important;flex-direction:row!important;gap:6px!important;box-sizing:border-box!important;width:100%!important;max-width:100%!important;overflow-x:auto!important;white-space:nowrap!important;padding:8px!important;background:var(--f650-surface)!important;border:1px solid var(--f650-border)!important;border-radius:14px!important}
            #ipsLayout_sideBar .ipsSideMenu_item,#ipsLayout_sideBar .cInboxNavigation li{flex:0 0 auto!important;margin:0!important}
            #ipsLayout_sideBar .ipsSideMenu_item>a,#ipsLayout_sideBar .cInboxNavigation a,#ipsLayout_sideBar #elMessengerNav a{display:block!important;min-height:42px!important;box-sizing:border-box!important;padding:10px 12px!important;border-radius:10px!important;background:var(--f650-panel)!important;color:var(--f650-text)!important;font-weight:600!important}
            #ipsLayout_sideBar .ipsSideMenu_itemActive>a,#ipsLayout_sideBar .ipsSideMenu_item_active>a,#ipsLayout_sideBar .cInboxNavigation .ipsTabs_active a{background:var(--f650-button)!important;color:var(--f650-button-text)!important}
            #ipsLayout_sideBar .ipsWidget{color:var(--f650-text)!important}
        """
        private const val CHATBOX_CSS = """
            #ipsLayout_body,#ipsLayout_contentWrapper,#ipsLayout_contentArea,#ipsLayout_mainArea{box-sizing:border-box!important;display:flex!important;flex-direction:column!important;min-height:calc(100vh - 16px)!important}
            #ipsLayout_contentWrapper,#ipsLayout_contentArea,#ipsLayout_mainArea{flex:1 1 auto!important}
            #ipsLayout_mainArea>[data-controller="bimChatBoxMain"]{display:flex!important;flex:1 1 auto!important;flex-direction:column!important;min-height:calc(100vh - 24px)!important}
            #elChatbox{display:none!important}
            #elChatboxContent{display:flex!important;flex:1 1 auto!important;flex-direction:column!important;min-height:0!important;background:var(--f650-surface)!important}
            #ipsTabs_elChatbox_chatbox_panel{display:flex!important;flex:1 1 auto!important;flex-direction:column!important;min-height:0!important;background:var(--f650-surface)!important}
            #chatboxWrap{box-sizing:border-box!important;min-height:300px!important;max-height:none!important;overflow-y:auto!important;background:var(--f650-bg)!important;color:var(--f650-text)!important}
            #loadMore{background:var(--f650-panel)!important;color:var(--f650-link)!important;border-bottom:1px solid var(--f650-border)!important}
            #chatcontent,#chatcontent>li,#chatcontent .ipsDataItem{background:var(--f650-surface)!important;color:var(--f650-text)!important;border-color:var(--f650-border)!important}
            #chatBoxForm{flex:0 0 auto!important;margin:8px 0 0!important;background:var(--f650-surface)!important;border:1px solid var(--f650-border)!important;border-radius:14px!important}
            #chatBoxForm .bimcb_chatArea,#chatBoxForm .ipsPad_half{background:var(--f650-surface)!important;color:var(--f650-text)!important}
            #cbCopyright{padding:8px!important;color:var(--f650-muted)!important}
        """
        private const val EDITOR_LIGHT_CSS = """
            html,body{background:#fff!important;color:#1b2b30!important;font:16px/1.5 Roboto,Arial,sans-serif!important}
            body.cke_editable{background:#fff!important;color:#1b2b30!important}
            p,li,div{color:#1b2b30!important}a{color:#145f86!important}
        """
        private const val EDITOR_DARK_CSS = """
            html,body{background:#202d31!important;background-color:#202d31!important;color:#e8efed!important;color-scheme:dark;font:16px/1.5 Roboto,Arial,sans-serif!important}
            body.cke_editable{background:#202d31!important;background-color:#202d31!important;color:#e8efed!important}
            p,li,div,span,strong,em,td,th{color:#e8efed!important}a{color:#a6cae0!important}
        """
        private const val REMOVE_SOCIAL_JS = """
            (function(){
              var domains=['facebook.com','twitter.com','x.com','plus.google.com','digg.com','del.icio.us','reddit.com','stumbleupon.com','pinterest.com','linkedin.com','instagram.com','tiktok.com','tumblr.com','vk.com','myspace.com','threads.net','bsky.app','mastodon.social','tapatalk.com','flipboard.com','whatsapp.com','telegram.org','t.me'];
              document.querySelectorAll('[data-controller="core.front.core.sharelink"]').forEach(function(list){var title=list.previousElementSibling;if(title&&title.tagName==='H5'&&/Udostępnij/.test(title.textContent))title.remove();list.remove()});
              document.querySelectorAll('a.cShareLink').forEach(function(a){var li=a.closest('li');if(li)li.remove();else a.remove()});
              var parents=new Set();
              document.querySelectorAll('a[href]').forEach(function(a){
                if(a.closest('article.ipsComment,.ipsStreamItem_snippet')) return;
                var host='';try{host=new URL(a.href,location.href).hostname.toLowerCase()}catch(e){}
                if(domains.some(function(d){return host===d||host.endsWith('.'+d)})){
                  var item=a.closest('li');if(item){parents.add(item.parentElement);item.remove()}else{a.remove()}
                }
              });
              document.querySelectorAll('#elFooterSocialLinks,.ipsSocial,.ipsShareLinks,[data-role=socialLinks]').forEach(function(e){e.remove()});
              parents.forEach(function(p){if(p&&!p.querySelector('a'))p.remove()});
            })()
        """
        private const val PREPARE_COMPOSER_JS = """
            (function(){
              function prepareComposer(){
                document.querySelectorAll('.ipsComposeArea').forEach(function(editor){
                  var form=editor.closest('form');var shell=form||editor;
                  shell.classList.add('f650gsComposer');
                  var mediaPattern=/wstaw\s+inne\s+media/i;
                  shell.querySelectorAll('button,a,[role="button"],span,div').forEach(function(node){
                    if(!mediaPattern.test((node.innerText||node.textContent||'').trim()))return;
                    if(node.querySelector('button,a,[role="button"]')&&node.tagName!=='BUTTON'&&node.tagName!=='A')return;
                    var control=node.closest('button,a,[role="button"]')||node;var candidate=control;
                    for(var parent=control.parentElement;parent&&parent!==shell;parent=parent.parentElement){
                      var otherControls=Array.from(parent.querySelectorAll('button,a,[role="button"],input,select,textarea,iframe,[contenteditable="true"]')).filter(function(x){return !control.contains(x)&&!x.contains(control)});
                      if(otherControls.length)break;
                      candidate=parent;
                    }
                    candidate.classList.add('f650gsMediaInsert');candidate.style.setProperty('display','none','important');
                  });
                  shell.querySelectorAll('.ipsFieldRow').forEach(function(row){
                    if(/powiadamiaj\s+mnie\s+o\s+odpowiedziach/i.test(row.innerText||row.textContent||''))row.classList.add('f650gsNotifyRow');
                  });
                });
              }
              prepareComposer();
              if(document.body)new MutationObserver(prepareComposer).observe(document.body,{childList:true,subtree:true});
            })()
        """
        private const val PREPARE_CHATBOX_JS = """
            (function(){
              function fillChatbox(){
                var wrap=document.getElementById('chatboxWrap');
                if(wrap){wrap.style.setProperty('height','calc(100vh - 160px)','important');wrap.style.setProperty('min-height','300px','important');wrap.style.setProperty('max-height','none','important');}
              }
              fillChatbox();
              if(document.body)new MutationObserver(fillChatbox).observe(document.body,{childList:true,subtree:true});
            })()
        """
    }

    private lateinit var webView: WebView
    private var loginFlow = false
    private var messageFlow = false
    private var chatboxFlow = false
    private var darkMode = false
    private var confirmedSignedIn: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loginFlow = intent.getBooleanExtra(EXTRA_LOGIN_FLOW, false)
        darkMode = intent.getBooleanExtra(EXTRA_DARK_MODE, false)
        val initialUrl = safeForumUrl(intent.getStringExtra(EXTRA_URL) ?: "http://f650gs.pl/")
        val initialPath = Uri.parse(initialUrl).path.orEmpty()
        messageFlow = initialPath.startsWith("/messenger/")
        chatboxFlow = initialPath.startsWith("/chatbox/")
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (darkMode) Color.rgb(17, 25, 28) else Color.rgb(241, 245, 248))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(5))
            setBackgroundColor(if (darkMode) Color.rgb(15, 39, 43) else TEAL)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.rgb(196, 216, 46))
            gravity = Gravity.CENTER
            contentDescription = "Wróć do aplikacji"
            setOnClickListener { finishWithSession() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(38), dp(36)))
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: when {
                loginFlow -> "Zaloguj się"
                initialUrl.contains("/chatbox/") -> "Chatbox"
                initialUrl.contains("/messenger/") -> "Wiadomości prywatne"
                else -> "Forum F650GS"
            }
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        header.addView(top)
        if (loginFlow) {
            header.addView(TextView(this).apply {
                text = "Logowanie odbywa się na stronie forum przez nieszyfrowane HTTP."
                textSize = 12f
                setTextColor(Color.rgb(220, 232, 229))
                setPadding(dp(50), 0, 0, 0)
            })
        }
        root.addView(header)

        webView = WebView(this).apply {
            setBackgroundColor(if (darkMode) Color.rgb(17, 25, 28) else Color.rgb(241, 245, 248))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = ForumClient()
        }
        if (chatboxFlow) {
            val frame = FrameLayout(this).apply {
                val surface = if (darkMode) Color.rgb(29, 41, 45) else Color.WHITE
                val outline = if (darkMode) Color.rgb(54, 72, 75) else Color.rgb(203, 215, 212)
                background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), outline)
                }
                setPadding(dp(1), dp(1), dp(1), dp(1))
                clipToOutline = true
                elevation = dp(2).toFloat()
            }
            frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
            root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        } else {
            root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        if (messageFlow || chatboxFlow) root.addView(buildAppNavigation(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.requestApplyInsets()
        webView.loadUrl(initialUrl)
    }

    private fun buildAppNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(if (darkMode) Color.rgb(29, 41, 45) else Color.WHITE)
            elevation = dp(8).toFloat()
        }
        val items = listOf(
            Triple("◷", "Ostatnie", "home"),
            Triple("▤", "Fora", "forums"),
            Triple("●", "Nieprzecz.", "unread"),
            Triple("☏", "Chatbox", "chatbox"),
            Triple("✉", "Wiad.", "messages"),
            Triple("☆", "Zapisane", "saved")
        )
        items.forEach { (icon, label, tab) ->
            val selected = if (chatboxFlow) tab == "chatbox" else tab == "messages"
            val color = when {
                selected && darkMode -> Color.rgb(151, 205, 195)
                selected -> Color.rgb(0, 102, 177)
                darkMode -> Color.rgb(185, 200, 201)
                else -> Color.rgb(88, 108, 113)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener { navigateToMainTab(tab) }
                addView(TextView(this@ForumWebActivity).apply {
                    text = icon
                    textSize = 19f
                    setTextColor(color)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@ForumWebActivity).apply {
                    text = label
                    textSize = 10f
                    setTextColor(color)
                    if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
            bar.addView(item, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        return bar
    }

    private fun navigateToMainTab(tab: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SELECT_TAB, tab)
        )
        finish()
    }

    private inner class ForumClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = routeUrl(request.url)

        @Deprecated("Deprecated in Android")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = routeUrl(Uri.parse(url))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!isForumHost(request.url.host)) {
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "".byteInputStream())
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            val pageCss = (if (darkMode) DARK_VARIABLES_CSS else LIGHT_VARIABLES_CSS) +
                BASE_PAGE_CSS +
                (if (darkMode) DARK_INLINE_BACKGROUND_CSS else "") +
                (if (loginFlow) LOGIN_CSS else "") +
                NATIVE_PAGE_CSS +
                (if (messageFlow) MESSAGE_NAV_CSS else HIDE_SIDE_NAV_CSS) +
                (if (chatboxFlow) CHATBOX_CSS else "") +
                COMPOSER_CSS + HIDE_SCROLLBARS_CSS
            val editorCss = if (darkMode) EDITOR_DARK_CSS else EDITOR_LIGHT_CSS
            val editorBackground = if (darkMode) "#202d31" else "#ffffff"
            val editorForeground = if (darkMode) "#e8efed" else "#1b2b30"
            view.evaluateJavascript(
                "(function(){var s=document.getElementById('f650gsNativeStyle');if(!s){s=document.createElement('style');s.id='f650gsNativeStyle';document.head.appendChild(s)}s.textContent=${org.json.JSONObject.quote(pageCss)};var editorCss=${org.json.JSONObject.quote(editorCss)};var editorBackground=${org.json.JSONObject.quote(editorBackground)};var editorForeground=${org.json.JSONObject.quote(editorForeground)};function styleEditors(){document.querySelectorAll('iframe').forEach(function(f){try{var d=f.contentDocument;if(!d||!d.head)return;var e=d.getElementById('f650gsEditorStyle');if(!e){e=d.createElement('style');e.id='f650gsEditorStyle';d.head.appendChild(e)}e.textContent=editorCss+' html,body,*{scrollbar-width:none!important;-ms-overflow-style:none!important} *::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}';[d.documentElement,d.body].filter(Boolean).forEach(function(node){node.style.setProperty('background-color',editorBackground,'important');node.style.setProperty('color',editorForeground,'important')})}catch(x){}})}styleEditors();setTimeout(styleEditors,250);document.querySelectorAll('iframe').forEach(function(f){f.addEventListener('load',styleEditors)});if(document.body)new MutationObserver(styleEditors).observe(document.body,{childList:true,subtree:true})})()",
                null
            )
            view.evaluateJavascript(REMOVE_SOCIAL_JS, null)
            view.evaluateJavascript(PREPARE_COMPOSER_JS, null)
            if (chatboxFlow) view.evaluateJavascript(PREPARE_CHATBOX_JS, null)
            view.evaluateJavascript(
                "!!document.querySelector('#elUserNav.cSignedIn') || !!document.querySelector('#elUserNav [data-role=\\\"signOut\\\"]')"
            ) { result ->
                val signedIn = result == "true"
                confirmedSignedIn = signedIn
                if (loginFlow && signedIn && !url.contains("/login/")) {
                    Toast.makeText(this@ForumWebActivity, "Zalogowano do forum", Toast.LENGTH_SHORT).show()
                    Handler(Looper.getMainLooper()).postDelayed({ finishWithSession() }, 700)
                }
            }
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        if (isForumHost(uri.host)) return false
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        return true
    }

    private fun finishWithSession() {
        CookieManager.getInstance().flush()
        if (confirmedSignedIn != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SIGNED_IN, confirmedSignedIn))
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    private fun safeForumUrl(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return "http://f650gs.pl/"
        return if (isForumHost(uri.host) && uri.scheme == "http") uri.toString() else "http://f650gs.pl/"
    }

    private fun isForumHost(host: String?): Boolean =
        host?.lowercase()?.let { it == FORUM_HOST || it.endsWith(".$FORUM_HOST") } == true

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Android; retained for API 24 back navigation")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finishWithSession()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
