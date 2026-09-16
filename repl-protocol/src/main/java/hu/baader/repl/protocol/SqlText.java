package hu.baader.repl.protocol;

/** Conservative lexical sanitization, not a SQL parser. Never stores literal/comment contents. */
public final class SqlText {
    private SqlText() {}
    public static String normalize(String sql) {
        if(sql==null || sql.isBlank()) return "<SQL unavailable>";
        if(sql.length()>65536) return "<SQL exceeds capture limit>";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<sql.length() && out.length()<4000;) {
            char c=sql.charAt(i);
            if(c=='-' && i+1<sql.length() && sql.charAt(i+1)=='-') { while(i<sql.length() && sql.charAt(i)!='\n') i++; out.append(' '); }
            else if(c=='/' && i+1<sql.length() && sql.charAt(i+1)=='*') {
                i+=2; int depth=1;
                while(i<sql.length() && depth>0) {
                    if(i+1<sql.length() && sql.startsWith("/*",i)) { depth++; i+=2; }
                    else if(i+1<sql.length() && sql.startsWith("*/",i)) { depth--; i+=2; } else i++;
                } out.append(' ');
            } else if(c=='\'') {
                i++; while(i<sql.length()) { char v=sql.charAt(i++); if(v=='\\' && i<sql.length()) i++; else if(v=='\'') { if(i<sql.length() && sql.charAt(i)=='\'') i++; else break; } }
                out.append('?');
            } else if(c=='$' && sql.substring(i).matches("(?s)^\\$(?:[a-zA-Z_][a-zA-Z_0-9]*)?\\$.*")) {
                int end=sql.indexOf('$',i+1); String tag=sql.substring(i,end+1); int close=sql.indexOf(tag,end+1);
                i=close<0 ? sql.length() : close+tag.length(); out.append('?');
            } else if(c=='"' || c=='`' || c=='[') {
                char close=c=='[' ? ']' : c; out.append(c); i++;
                while(i<sql.length()) { char v=sql.charAt(i++); out.append(v); if(v==close) { if(i<sql.length() && sql.charAt(i)==close) out.append(sql.charAt(i++)); else break; } }
            } else if(Character.isDigit(c) && (i==0 || !Character.isJavaIdentifierPart(sql.charAt(i-1)))) {
                i++; while(i<sql.length() && (Character.isDigit(sql.charAt(i)) || ".eExXaAbBcCdDfF+-".indexOf(sql.charAt(i))>=0)) i++; out.append('?');
            } else { out.append(Character.isWhitespace(c) ? ' ' : Character.toLowerCase(c)); i++; }
        }
        if(out.length()>=4000) return "<SQL exceeds capture limit>";
        return out.toString().replaceAll(" +"," ").trim();
    }
    public static String format(String sql) {
        return sql.replaceAll("(?i) (from|where|left join|right join|inner join|join|group by|order by|having|limit|values|set) ","\n$1 ");
    }
}
