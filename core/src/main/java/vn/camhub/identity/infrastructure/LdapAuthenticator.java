package vn.camhub.identity.infrastructure;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.springframework.stereotype.Component;

/**
 * Xác thực và tra cứu LDAP/Active Directory bằng JNDI (US-44): bind tài khoản dịch vụ → tìm người dùng → bind bằng mật
 * khẩu người dùng; đọc tên, email, nhóm (theo thuộc tính thành viên của nhóm). Hỗ trợ ldap:// và ldaps://.
 */
@Component
public class LdapAuthenticator {

    static final String TIMEOUT_MS = "5000";

    /** Cấu hình kết nối (mật khẩu dịch vụ đã giải mã, chỉ trong bộ nhớ). */
    public record Config(String url, String bindDn, String bindPassword, String userBase, String userFilter,
            String groupBase, String memberAttribute, String displayNameAttribute, String mailAttribute,
            String disabledGroupDn) {
    }

    public record DirectoryUser(String dn, String displayName, String mail, List<String> groups, boolean disabled) {
    }

    /** Không kết nối được máy chủ thư mục (US-44 AC3). */
    public static class DirectoryUnavailable extends RuntimeException {
        public DirectoryUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Mật khẩu người dùng sai hoặc không có người dùng; rỗng nếu không xác thực được. */
    public Optional<DirectoryUser> authenticate(Config c, String login, String password) {
        if (login == null || login.isBlank() || password == null || password.isEmpty()) {
            return Optional.empty(); // bind rỗng mật khẩu = bind ẩn danh, luôn "thành công": phải chặn
        }
        Optional<DirectoryUser> user = findByLogin(c, login.trim());
        if (user.isEmpty()) {
            return Optional.empty();
        }
        try {
            close(context(c.url(), user.get().dn(), password));
            return user;
        } catch (AuthenticationException e) {
            return Optional.empty();
        } catch (NamingException e) {
            throw unavailable(e);
        }
    }

    public Optional<DirectoryUser> findByLogin(Config c, String login) {
        return search(c, c.userFilter().replace("{0}", escape(login)), c.userBase(), SearchControls.SUBTREE_SCOPE);
    }

    /** Tra người dùng theo DN đã lưu (đồng bộ định kỳ); rỗng nếu đã bị xóa khỏi thư mục. */
    public Optional<DirectoryUser> findByDn(Config c, String dn) {
        return search(c, "(objectClass=*)", dn, SearchControls.OBJECT_SCOPE);
    }

    /** Kiểm tra kết nối: bind tài khoản dịch vụ và đếm người dùng (tối đa 1000). */
    public int test(Config c) {
        DirContext ctx = service(c);
        try {
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(1000);
            sc.setReturningAttributes(new String[] {"dn"});
            int n = 0;
            NamingEnumeration<SearchResult> r = ctx.search(c.userBase(), c.userFilter().replace("{0}", "*"), sc);
            try {
                while (r.hasMore()) {
                    r.next();
                    n++;
                }
            } catch (javax.naming.SizeLimitExceededException e) {
                // đủ để biết kết nối hoạt động
            }
            return n;
        } catch (NamingException e) {
            throw unavailable(e);
        } finally {
            close(ctx);
        }
    }

    private Optional<DirectoryUser> search(Config c, String filter, String base, int scope) {
        DirContext ctx = service(c);
        try {
            SearchControls sc = new SearchControls();
            sc.setSearchScope(scope);
            sc.setCountLimit(2);
            // Thuộc tính khóa tài khoản phải yêu cầu đích danh (thuộc tính vận hành, "*" không trả về)
            sc.setReturningAttributes(new String[] {c.displayNameAttribute(), c.mailAttribute(), "pwdAccountLockedTime",
                    "userAccountControl"});
            NamingEnumeration<SearchResult> r;
            try {
                r = ctx.search(base, filter, sc);
            } catch (javax.naming.NameNotFoundException e) {
                return Optional.empty();
            }
            if (!r.hasMore()) {
                return Optional.empty();
            }
            SearchResult sr = r.next();
            if (r.hasMore()) {
                return Optional.empty(); // tên đăng nhập trùng nhiều tài khoản: không đoán
            }
            String dn = sr.getNameInNamespace();
            Attributes a = sr.getAttributes();
            List<String> groups = groupsOf(ctx, c, dn);
            boolean disabled = locked(a) || (c.disabledGroupDn() != null && !c.disabledGroupDn().isBlank()
                    && groups.stream().anyMatch(g -> g.equalsIgnoreCase(c.disabledGroupDn())));
            return Optional.of(new DirectoryUser(dn, attr(a, c.displayNameAttribute()), attr(a, c.mailAttribute()),
                    groups, disabled));
        } catch (javax.naming.SizeLimitExceededException e) {
            return Optional.empty();
        } catch (NamingException e) {
            throw unavailable(e);
        } finally {
            close(ctx);
        }
    }

    private List<String> groupsOf(DirContext ctx, Config c, String userDn) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(new String[] {"dn"});
        List<String> out = new ArrayList<>();
        NamingEnumeration<SearchResult> r = ctx.search(c.groupBase(), "(" + c.memberAttribute() + "={0})",
                new Object[] {userDn}, sc);
        while (r.hasMore()) {
            out.add(r.next().getNameInNamespace());
        }
        return out;
    }

    private DirContext service(Config c) {
        try {
            return context(c.url(), c.bindDn(), c.bindPassword());
        } catch (NamingException e) {
            throw unavailable(e);
        }
    }

    private static DirContext context(String url, String dn, String password) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, dn);
        env.put(Context.SECURITY_CREDENTIALS, password == null ? "" : password);
        env.put("com.sun.jndi.ldap.connect.timeout", TIMEOUT_MS);
        env.put("com.sun.jndi.ldap.read.timeout", TIMEOUT_MS);
        env.put(Context.REFERRAL, "ignore");
        return new InitialDirContext(env);
    }

    private static DirectoryUnavailable unavailable(NamingException e) {
        String msg = e instanceof CommunicationException ? "Không kết nối được máy chủ thư mục"
                : e instanceof AuthenticationException ? "Tài khoản dịch vụ LDAP bị từ chối" : "Lỗi thư mục: "
                        + e.getMessage();
        return new DirectoryUnavailable(msg, e);
    }

    /**
     * Tài khoản bị vô hiệu hóa trên thư mục: OpenLDAP ppolicy ({@code pwdAccountLockedTime}) hoặc Active Directory
     * ({@code userAccountControl} có bit ACCOUNTDISABLE = 2).
     */
    static boolean locked(Attributes a) throws NamingException {
        if (a.get("pwdAccountLockedTime") != null) {
            return true;
        }
        String uac = attr(a, "userAccountControl");
        try {
            return uac != null && (Long.parseLong(uac.trim()) & 2L) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String attr(Attributes a, String name) throws NamingException {
        Attribute at = a.get(name);
        return at == null || at.get() == null ? null : at.get().toString();
    }

    /** Thoát ký tự đặc biệt trong bộ lọc LDAP (RFC 4515) để chặn LDAP injection. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void close(DirContext ctx) {
        try {
            if (ctx != null) {
                ctx.close();
            }
        } catch (NamingException ignored) {
            // đóng kết nối lỗi không ảnh hưởng kết quả
        }
    }
}
