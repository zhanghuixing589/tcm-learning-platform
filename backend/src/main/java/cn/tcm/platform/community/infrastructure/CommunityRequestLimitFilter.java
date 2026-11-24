package cn.tcm.platform.community.infrastructure;
import cn.tcm.platform.community.application.CommunityTextPolicy;
import cn.tcm.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.IntSupplier;
/** Bounded JSON buffering after security/CORS and before MVC/Jackson. Multipart keeps existing limits. */
@Component @Order(-90)
public class CommunityRequestLimitFilter extends OncePerRequestFilter {
 private final ObjectMapper json;private final IntSupplier limit;
 @Autowired public CommunityRequestLimitFilter(ObjectMapper json,CommunityTextPolicy policies){this(json,()->policies.current().limit("maxRequestBytes"));}
 public CommunityRequestLimitFilter(ObjectMapper json,IntSupplier limit){this.json=json;this.limit=limit;}
 @Override protected boolean shouldNotFilter(HttpServletRequest request){String type=request.getContentType();if(type==null||request.getMethod().equals("OPTIONS"))return true;type=type.split(";",2)[0].strip().toLowerCase(Locale.ROOT);return !type.equals("application/json")&&!type.endsWith("+json");}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String encoding=req.getHeader("Content-Encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity")){error(res,400,"VALIDATION_ERROR","不支持压缩请求体");return;}
  int max=configurableContentRoute(req.getRequestURI())?Math.max(1,Math.min(limit.getAsInt(),CommunityTextPolicy.MAX_REQUEST_BYTES)):CommunityTextPolicy.MAX_REQUEST_BYTES;byte[] body=req.getInputStream().readNBytes(max+1);
  if(body.length>max){error(res,413,"PAYLOAD_TOO_LARGE","请求体字节数超出上限");return;}
  chain.doFilter(new BufferedRequest(req,body),res);
 }
 private static boolean configurableContentRoute(String path){return path.equals("/api/v1/posts")||path.startsWith("/api/v1/posts/")||path.startsWith("/api/v1/comments/")||path.equals("/api/v1/me/articles")||path.startsWith("/api/v1/me/articles/")||path.startsWith("/api/v1/community/");}
 private void error(HttpServletResponse res,int status,String code,String message) throws IOException {res.setStatus(status);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),ApiResponse.error(code,message));}
 private static final class BufferedRequest extends HttpServletRequestWrapper {
  private final byte[] body;BufferedRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
  @Override public int getContentLength(){return body.length;}
  @Override public long getContentLengthLong(){return body.length;}
  @Override public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public int read(byte[] b,int off,int len){return in.read(b,off,len);}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON input only");}};}
  @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
 }
}
