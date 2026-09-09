package com.synechis.fulfillment.runtime;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class CorrelationFilter extends OncePerRequestFilter {
 @org.springframework.beans.factory.annotation.Autowired(required=false) private io.micrometer.tracing.Tracer tracer;
 protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws java.io.IOException,ServletException{
  String id=request.getHeader("X-Correlation-ID"),trace=request.getHeader("traceparent");
  if(id==null||!id.matches("[A-Za-z0-9-]{1,64}"))id=UUID.randomUUID().toString();
  if(trace==null||!trace.matches("00-[a-f0-9]{32}-[a-f0-9]{16}-0[01]"))trace="00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";
  if(tracer!=null&&tracer.currentSpan()!=null){var context=tracer.currentSpan().context();trace="00-"+context.traceId()+"-"+context.spanId()+"-01";}
  Correlation.set(Map.of("correlationId",id,"traceparent",trace));response.setHeader("X-Correlation-ID",id);
  try{chain.doFilter(request,response);}finally{Correlation.clear();}
 }
}
