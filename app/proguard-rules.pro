# LibVLC's JNI entry points and callbacks use Java class/member names.
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }

# Preserve SMB authentication and crypto provider reflection conservatively.
# This deliberately does not attempt to strip algorithms from dependencies.
-keep class jcifs.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Optional desktop/server APIs absent on Android; app uses NTLM, not JAAS/Kerberos/servlets.
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.kerberos.KerberosKey
-dontwarn javax.security.auth.kerberos.KerberosPrincipal
-dontwarn javax.security.auth.kerberos.KerberosTicket
-dontwarn javax.security.auth.login.AppConfigurationEntry$LoginModuleControlFlag
-dontwarn javax.security.auth.login.AppConfigurationEntry
-dontwarn javax.security.auth.login.Configuration
-dontwarn javax.security.auth.login.LoginContext
-dontwarn javax.servlet.Filter
-dontwarn javax.servlet.FilterChain
-dontwarn javax.servlet.FilterConfig
-dontwarn javax.servlet.ServletConfig
-dontwarn javax.servlet.ServletException
-dontwarn javax.servlet.ServletOutputStream
-dontwarn javax.servlet.ServletRequest
-dontwarn javax.servlet.ServletResponse
-dontwarn javax.servlet.http.HttpServlet
-dontwarn javax.servlet.http.HttpServletRequest
-dontwarn javax.servlet.http.HttpServletRequestWrapper
-dontwarn javax.servlet.http.HttpServletResponse
-dontwarn javax.servlet.http.HttpSession
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
