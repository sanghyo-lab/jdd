"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api, dateLabel } from "@/lib/client-api";

type Product = { id: string; name: string; price: number; stockQuantity: number };
type Coupon = { id: string; status: string; discountType: string; minOrderAmount: number; fixedDiscountAmount: number | null; discountRate: number | null; validUntil: string };
type Order = { id: string; customerId: string; checkoutKey: string; status: string; items: { productId: string; quantity: number; unitPrice: number; lineAmount: number }[]; subtotal: number; discountAmount: number; totalAmount: number; customerCouponId: string | null; createdAt: string };
type Payment = { id: string; status: string; method: string; amount: number; requestKey: string };
type Refund = { id: string; status: string; amount: number; failureCode: string | null; requestKey: string };
type OrderIntent = { customerId: string; checkoutKey: string; customerCouponId: string | null; items: { productId: string; quantity: number }[] };
type ActionIntent = { orderId: string; kind: "payments" | "cancel"; body: { requestKey: string; method?: string; reason?: string } };
const money = (amount: number) => amount.toLocaleString("ko-KR") + "원";
const orderLabels: Record<string, string> = { PAYMENT_PENDING: "결제 대기", PAID: "결제 완료", CANCELLED: "취소 완료" };
export function Shop() {
  const [products, setProducts] = useState<Product[]>([]); const [coupons, setCoupons] = useState<Coupon[]>([]); const [orders, setOrders] = useState<Order[]>([]);
  const [customer, setCustomer] = useState(""); const [couponCustomer, setCouponCustomer] = useState(""); const [selected, setSelected] = useState<Order | null>(null);
  const [intent, setIntent] = useState<OrderIntent | null>(null); const [action, setAction] = useState<ActionIntent | null>(null);
  const [payment, setPayment] = useState<Payment | null>(null); const [refund, setRefund] = useState<Refund | null | undefined>(undefined);
  const [error, setError] = useState(""); const [notice, setNotice] = useState(""); const [busy, setBusy] = useState(false); const working = useRef(false);
  async function refreshProducts() { const data = await api<{ items: Product[] }>("/api/commerce/products?limit=100"); setProducts(data.items); }
  useEffect(() => { void refreshProducts().catch(e => setError(e.message));
    try { const saved = JSON.parse(sessionStorage.getItem("jdd-shop-order") ?? "null"); if (saved?.checkoutKey && saved?.customerId && saved?.items?.length) { setIntent(saved); setCustomer(saved.customerId); } } catch { /* Invalid private browser state is not a server result. */ }
  }, []);
  async function run(job: () => Promise<void>) {
    if (working.current) return; working.current = true; setBusy(true); setError(""); setNotice("");
    try { await job(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); working.current = false; }
  }
  async function findOrders() {
    if (!customer.trim()) throw Error("고객 번호를 입력하세요.");
    const data = await api<{items:Order[]}>("/api/commerce/orders?limit=100&customerId=" + encodeURIComponent(customer)); setOrders(data.items);
  }
  async function submitOrder(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    void run(async () => {
      let request = intent;
      if (!request) {
        const quantity = Number(form.get("quantity")); const productId = String(form.get("productId") ?? "");
        if (!customer.trim() || !productId || !Number.isSafeInteger(quantity) || quantity < 1) throw Error("고객·상품과 1 이상의 정수 수량을 확인하세요.");
        request = { customerId: customer, checkoutKey: crypto.randomUUID(), customerCouponId: String(form.get("couponId") ?? "") || null, items: [{productId, quantity}] };
        sessionStorage.setItem("jdd-shop-order", JSON.stringify(request)); setIntent(request);
      }
      const order = await api<Order>("/api/commerce/orders", { method: "POST", body: JSON.stringify(request) });
      choose(order); setNotice("주문 응답을 받았습니다. 주문 번호와 재고·금액을 확인하세요.");
      setOrders(previous => [order, ...previous.filter(item => item.id !== order.id)]); await refreshProducts();
    });
  }
  function choose(order: Order) { setSelected(order); setAction(null); setPayment(null); setRefund(undefined); }
  async function orderAction(kind: "payments" | "cancel", form: FormData) {
    if (!selected) return;
    await run(async () => {
      const request = action?.orderId === selected.id && action.kind === kind ? action : { orderId: selected.id, kind,
        body: kind === "payments" ? { requestKey: crypto.randomUUID(), method: String(form.get("method")) } : { requestKey: crypto.randomUUID(), reason: String(form.get("reason")) } };
      if (kind === "cancel" && !request.body.reason?.trim()) throw Error("취소 사유를 입력하세요.");
      setAction(request);
      const response = await api<{order:Order;payment?:Payment;refund?:Refund|null}>("/api/commerce/orders/" + encodeURIComponent(request.orderId) + "/" + kind, { method:"POST",body:JSON.stringify(request.body) });
      if (response.payment) setPayment(response.payment); if ("refund" in response) setRefund(response.refund);
      // A same-key replay can return the original action snapshot. GET gives the current order state.
      const current = await api<Order>("/api/commerce/orders/" + encodeURIComponent(request.orderId)); setSelected(current);
      setOrders(previous => [current, ...previous.filter(item => item.id !== current.id)]); await refreshProducts();
      setNotice(kind === "payments" ? "결제 응답과 현재 주문 상태를 확인했습니다." : "취소 응답을 확인했습니다. 환불 결과는 별도로 확인하세요.");
    });
  }
  return <><div className="page-heading"><div><p className="eyebrow">커머스 시연</p><h1>주문과 처리 결과</h1><p className="muted">합성 상품으로 주문·쿠폰·모의 결제·취소·환불 결과를 확인합니다.</p></div><Link className="button-link primary" href="/tickets">문의 작업실</Link></div>
    {error && <div className="notice error" role="alert">{error}<p className="small">응답을 확인하지 못했다면 주문 목록과 현재 상태를 먼저 조회하세요.</p></div>}{notice && <div className="notice success" role="status">{notice}</div>}
    <div className="shop-columns"><section className="panel"><div className="panel-heading"><h2>주문 만들기</h2><button disabled={busy} onClick={() => void run(refreshProducts)}>상품·재고 새로고침</button></div>
      <form className="shop-product-search" onSubmit={event=>{event.preventDefault();const data=new FormData(event.currentTarget);void run(async()=>{const product=await api<Product>("/api/commerce/products/"+encodeURIComponent(String(data.get("lookupProductId"))));setProducts(previous=>[product,...previous.filter(item=>item.id!==product.id)]);setNotice("상품을 조회했습니다. 상품 선택에서 확인하세요.");});}}><label>상품 번호 찾기<input name="lookupProductId" required maxLength={200}/></label><button disabled={busy}>상품 번호로 조회</button><p className="small muted">목록에는 처음 100개를 표시합니다. 찾는 상품이 없으면 번호로 조회하세요.</p></form>
      <form className="stack" onSubmit={submitOrder}><label>고객 번호<input name="customerId" required maxLength={200} value={customer} disabled={!!intent} onChange={e=>setCustomer(e.target.value)} placeholder="합성 고객 식별자" /></label>
        <label>상품<select name="productId" required disabled={!!intent}><option value="">상품 선택</option>{products.map(product=><option key={product.id} value={product.id}>{product.name} · {money(product.price)} · 재고 {product.stockQuantity}개</option>)}</select></label>
        <label>수량<input name="quantity" type="number" min="1" step="1" required defaultValue={1} disabled={!!intent} /></label>
        <button type="button" className="secondary align-start" disabled={busy || !!intent} onClick={()=>void run(async()=>{if(!customer.trim())throw Error("고객 번호를 먼저 입력하세요.");const data=await api<{items:Coupon[]}>("/api/commerce/customers/"+encodeURIComponent(customer)+"/coupons?limit=100");setCoupons(data.items);setCouponCustomer(customer);setNotice("고객 쿠폰을 조회했습니다.");})}>고객 쿠폰 조회</button>
        <label>적용 쿠폰<select name="couponId" disabled={!!intent || couponCustomer!==customer}><option value="">쿠폰 사용 안 함</option>{coupons.map(coupon=><option key={coupon.id} value={coupon.id}>{coupon.id} · {coupon.status} · {coupon.discountType==='FIXED'?money(coupon.fixedDiscountAmount??0):(coupon.discountRate??0)+'%'} · 최소 {money(coupon.minOrderAmount)}</option>)}</select></label>
        {couponCustomer===customer && <ul className="small coupon-list">{coupons.map(coupon=><li key={coupon.id}>{coupon.id} · {coupon.status} · 만료 {dateLabel(coupon.validUntil)}</li>)}</ul>}
        {intent && <div className="notice warning"><strong>저장된 주문 요청</strong><p className="small">고객 {intent.customerId}<br/>상품 {intent.items.map(item=>item.productId+' × '+item.quantity).join(', ')}<br/>쿠폰 {intent.customerCouponId??'없음'}<br/>체크아웃 키 {intent.checkoutKey}</p><p className="small">같은 요청의 재전송은 위 입력을 그대로 사용합니다. 중복 주문 결함의 시연 대상 경로입니다.</p></div>}
        <div className="shop-actions"><button className="primary" disabled={busy}>{intent?'같은 주문 요청 다시 전송':'주문 생성'}</button>{intent && <button type="button" disabled={busy} onClick={()=>{sessionStorage.removeItem('jdd-shop-order');setIntent(null);setNotice('새 주문은 새 체크아웃 키로 생성됩니다.');}}>새 주문 입력</button>}</div>
      </form></section><section className="panel"><div className="panel-heading"><h2>주문 조회</h2></div><form className="stack" onSubmit={event=>{event.preventDefault();const form=new FormData(event.currentTarget);void run(async()=>{const order=await api<Order>('/api/commerce/orders/'+encodeURIComponent(String(form.get('orderId'))));choose(order);});}}><label>주문 번호<input name="orderId" required maxLength={200}/></label><div className="shop-actions"><button disabled={busy}>주문 번호로 조회</button><button type="button" disabled={busy} onClick={()=>void run(findOrders)}>입력 고객의 주문 조회</button></div></form>
        <ul className="shop-orders">{orders.map(order=><li key={order.id}><button disabled={busy} className="history-choice" aria-pressed={selected?.id===order.id} onClick={()=>choose(order)}><strong>{orderLabels[order.status]??order.status} · {money(order.totalAmount)}</strong><span className="small">{order.id}</span><time className="small muted">{dateLabel(order.createdAt)}</time></button></li>)}</ul>
      </section></div>
    {selected && <section className="panel order-detail"><div className="panel-heading"><h2>선택 주문 · {orderLabels[selected.status]??selected.status}</h2><button disabled={busy} onClick={()=>void run(async()=>setSelected(await api<Order>('/api/commerce/orders/'+encodeURIComponent(selected.id))))}>현재 주문 다시 조회</button></div>
      <dl className="source-details"><dt>주문 번호</dt><dd>{selected.id}</dd><dt>고객</dt><dd>{selected.customerId}</dd><dt>체크아웃 키</dt><dd>{selected.checkoutKey}</dd></dl>
      <div className="table-scroll"><table><thead><tr><th>상품</th><th>수량</th><th>단가</th><th>합계</th></tr></thead><tbody>{selected.items.map(item=><tr key={item.productId}><td>{item.productId}</td><td>{item.quantity}</td><td>{money(item.unitPrice)}</td><td>{money(item.lineAmount)}</td></tr>)}</tbody></table></div>
      <p className="order-totals">상품 합계 {money(selected.subtotal)} · 할인 {money(selected.discountAmount)} · <strong>결제 금액 {money(selected.totalAmount)}</strong></p>
      <div className="form-grid"><form className="stack" onSubmit={event=>{event.preventDefault();void orderAction('payments',new FormData(event.currentTarget));}}><h3>모의 결제</h3><label>결제 수단<select name="method" disabled={action?.kind==='payments'}><option value="CARD">카드</option><option value="EASY_PAY">간편결제</option></select></label><button disabled={busy || selected.status==='CANCELLED'}>{action?.kind==='payments'?'같은 결제 요청 다시 전송':'결제 요청'}</button>{payment && <p className="notice success">결제 상태: {payment.status} · {payment.method} · {money(payment.amount)}<br/><span className="small">{payment.id}</span></p>}</form>
        <form className="stack" onSubmit={event=>{event.preventDefault();void orderAction('cancel',new FormData(event.currentTarget));}}><h3>전체 취소와 환불</h3><label>취소 사유<input name="reason" required maxLength={2000} disabled={action?.kind==='cancel'} defaultValue="합성 주문 시연 취소"/></label><button disabled={busy}>{action?.kind==='cancel'?'같은 취소 요청 다시 전송':'전체 주문 취소'}</button>
          {refund!==undefined && <div className={'notice '+(refund?.status==='FAILED'?'warning':'success')}>{refund ? <>환불 상태: {refund.status} · {money(refund.amount)}<br/><span className="small">{refund.id}{refund.failureCode && ' · '+refund.failureCode}</span></> : '취소 응답의 환불 기록이 없습니다. 결제 여부와 조사 근거를 확인하세요.'}</div>}</form></div>
      <p className="small muted">주문 취소 상태와 환불 상태는 별개입니다. 조회만 한 주문의 결제·환불 이력은 이 화면에서 추정하지 않습니다.</p><Link className="inline-link" href="/tickets#new-ticket">문의 등록으로 이동 — 위 주문·고객 번호를 참고 정보에 입력</Link>
    </section>}
  </>;
}
