package com.synechis.fulfillment.runtime;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
@RestControllerAdvice
public class Problems {
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<ProblemDetail> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(ProblemDetail.forStatusAndDetail(e.getStatusCode(),e.getReason()==null?"Request rejected":e.getReason()));}
 @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) ResponseEntity<ProblemDetail> bad(Exception e){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid request"));}
 @ExceptionHandler(ConcurrencyFailureException.class) ResponseEntity<ProblemDetail> conflict(Exception e){return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Concurrent change; reload version"));}
}
