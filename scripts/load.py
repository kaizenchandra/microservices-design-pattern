#!/usr/bin/env python3
"""Bounded stdlib load driver. Measures acceptance latency and terminal completion; no invented results."""
import argparse,concurrent.futures,json,os,statistics,time,urllib.request,urllib.error,uuid
p=argparse.ArgumentParser();p.add_argument('--orders',type=int,default=30);p.add_argument('--rate',type=float,default=2);p.add_argument('--url',default='http://localhost:8080');p.add_argument('--timeout',type=float,default=180);args=p.parse_args()
if args.orders<1 or args.rate<=0: p.error('orders and rate must be positive')
token=os.environ['TOKEN'];latencies=[];outcomes={};ids=[]
def request(path,body=None):
 r=urllib.request.Request(args.url+path,data=json.dumps(body).encode() if body else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
 with urllib.request.urlopen(r,timeout=10) as response:return json.load(response)
def create(_):
 start=time.monotonic()
 try:
  result=request('/api/orders',dict(sku='DEMO',quantity=1,amount=19.99,currency='USD',paymentMode='SUCCESS',shippingMode='SUCCESS'));return result['orderId'],(time.monotonic()-start)*1000,None
 except Exception as e:return None,(time.monotonic()-start)*1000,type(e).__name__
started=time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
 futures=[]
 for i in range(args.orders):
  delay=started+i/args.rate-time.monotonic()
  if delay>0:time.sleep(delay)
  futures.append(executor.submit(create,i))
 for f in futures:
  order,ms,error=f.result();latencies.append(ms)
  if error:outcomes[error]=outcomes.get(error,0)+1
  else:ids.append(order)
deadline=time.monotonic()+args.timeout
while ids and time.monotonic()<deadline:
 for order in list(ids):
  try:
   result=request('/api/orders/'+order);state=result['order']['status']
   if state in ['CONFIRMED','FAILED','CANCELLED','MANUAL_RECOVERY']:outcomes[state]=outcomes.get(state,0)+1;ids.remove(order)
  except urllib.error.HTTPError as e:outcomes['poll_http_'+str(e.code)]=outcomes.get('poll_http_'+str(e.code),0)+1
  time.sleep(.15) # respects the shared edge limit; not a correctness-test synchronizer
outcomes['unfinished']=len(ids);ordered=sorted(latencies)
print(json.dumps({'orders':args.orders,'arrival_rate_per_second':args.rate,'max_workers':8,'elapsed_seconds':time.monotonic()-started,'acceptance_ms':{'median':statistics.median(latencies),'p95':ordered[min(len(ordered)-1,int(len(ordered)*.95))],'max':max(latencies)},'outcomes':outcomes},indent=2))
