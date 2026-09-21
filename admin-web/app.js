const SUPABASE_URL = 'https://sidvjcxiyopnneldkqti.supabase.co';
const SUPABASE_KEY = 'sb_publishable_407xvG4HTNudr-o06CbrYw_-Eh92OBy';
const $ = id => document.getElementById(id);
let session = readSession();
let clinics = [];
let pendingPayments = [];
let paymentHistory = [];
let events = [];
let summary = null;

function readSession(){ try{return JSON.parse(sessionStorage.getItem('clinic_admin_session')||'null')}catch{return null} }
function saveSession(s){ session=s; sessionStorage.setItem('clinic_admin_session',JSON.stringify(s)); }
function clearSession(){ session=null; sessionStorage.removeItem('clinic_admin_session'); }
function authHeaders(){ return {'apikey':SUPABASE_KEY,'Authorization':`Bearer ${session?.access_token||''}`,'Content-Type':'application/json','Accept':'application/json'}; }

async function authRequest(path, body){
  const res=await fetch(SUPABASE_URL+path,{method:'POST',headers:{'apikey':SUPABASE_KEY,'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify(body)});
  const data=await safeJson(res);
  if(!res.ok) throw new Error(errorText(data,res.status));
  return data;
}

async function refreshSession(){
  if(!session?.refresh_token) return false;
  try{
    const data=await authRequest('/auth/v1/token?grant_type=refresh_token',{refresh_token:session.refresh_token});
    saveSession(data); return true;
  }catch{ clearSession(); return false; }
}

async function api(path,{method='GET',body=null,prefer=''}={}){
  if(!session?.access_token) throw new Error('not_signed_in');
  const run=()=>fetch(SUPABASE_URL+path,{method,headers:{...authHeaders(),...(prefer?{'Prefer':prefer}:{})},body:body==null?undefined:JSON.stringify(body)});
  let res=await run();
  if(res.status===401 && await refreshSession()) res=await run();
  const data=await safeJson(res);
  if(!res.ok) throw new Error(errorText(data,res.status));
  return data;
}

async function safeJson(res){ const t=await res.text(); if(!t) return null; try{return JSON.parse(t)}catch{return t} }
function errorText(data,status){ if(data&&typeof data==='object') return data.message||data.msg||data.error_description||data.error||`HTTP ${status}`; return `HTTP ${status}`; }
function enc(v){ return encodeURIComponent(v??''); }
function esc(v){ return String(v??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c])); }
function fmtDate(v){ if(!v) return '—'; const d=new Date(v); return isNaN(d)?esc(v):d.toLocaleString('ar-EG',{dateStyle:'medium',timeStyle:'short'}); }
function money(v,c='SDG'){ return `${Number(v||0).toLocaleString('ar-EG')} ${esc(c)}`; }
function statusLabel(v){ return ({trialing:'تجربة',active:'نشط',past_due:'متأخر',suspended:'موقوف',cancelled:'ملغي'})[v]||v||'—'; }
function paymentStatusLabel(v){ return ({pending:'معلق',confirmed:'مؤكد',rejected:'مرفوض'})[v]||v||'—'; }
function eventLabel(v){ return ({payment_confirmed:'تأكيد دفعة',payment_rejected:'رفض دفعة',status_changed:'تغيير حالة'})[v]||v||'—'; }
function msg(target,text,type=''){ target.textContent=text||''; target.className=`message ${type}`; }

async function signIn(){
  const email=$('email').value.trim(), password=$('password').value;
  if(!email||!password) return msg($('authMessage'),'اكتبي البريد وكلمة المرور','error');
  msg($('authMessage'),'جاري الدخول…');
  try{
    const data=await authRequest('/auth/v1/token?grant_type=password',{email,password});
    saveSession(data); await enterAdmin();
  }catch(e){ msg($('authMessage'),friendly(e),'error'); }
}

async function signUp(){
  const email=$('email').value.trim(), password=$('password').value;
  if(!email||password.length<6) return msg($('authMessage'),'اكتبي بريدًا صحيحًا وكلمة مرور 6 أحرف أو أكثر','error');
  msg($('authMessage'),'جاري إنشاء الحساب…');
  try{
    const data=await authRequest('/auth/v1/signup',{email,password});
    if(data?.access_token){ saveSession(data); await enterAdmin(); }
    else msg($('authMessage'),'تم إنشاء الحساب. أكدي البريد ثم سجلي الدخول.','success');
  }catch(e){ msg($('authMessage'),friendly(e),'error'); }
}

async function enterAdmin(){
  try{
    const uid=session?.user?.id;
    if(!uid) throw new Error('تعذر قراءة الحساب');
    const rows=await api(`/rest/v1/platform_admins?select=user_id,display_name&user_id=eq.${enc(uid)}&limit=1`);
    $('loginView').classList.add('hidden');
    if(!Array.isArray(rows)||rows.length===0){
      $('noAccessView').classList.remove('hidden'); $('dashboard').classList.add('hidden'); msg($('claimMessage'),''); return;
    }
    $('noAccessView').classList.add('hidden'); $('dashboard').classList.remove('hidden');
    await loadAll();
  }catch(e){
    clearSession(); $('dashboard').classList.add('hidden'); $('noAccessView').classList.add('hidden'); $('loginView').classList.remove('hidden'); msg($('authMessage'),friendly(e),'error');
  }
}

async function claimInitialAdmin(){
  const code=$('adminSetupCode').value.trim(), displayName=$('adminDisplayName').value.trim();
  if(!code) return msg($('claimMessage'),'أدخلي رمز تهيئة أول مدير','error');
  msg($('claimMessage'),'جاري تهيئة حساب الإدارة…');
  try{
    await api('/rest/v1/rpc/claim_initial_platform_admin',{method:'POST',body:{p_setup_code:code,p_display_name:displayName}});
    $('adminSetupCode').value='';
    msg($('claimMessage'),'تم تفعيل حساب الإدارة','success');
    await enterAdmin();
  }catch(e){ msg($('claimMessage'),friendly(e),'error'); }
}

async function loadAll(){
  msg($('globalMessage'),'جاري تحديث البيانات…');
  try{
    const [c,p,h,e,s]=await Promise.all([
      api('/rest/v1/clinics?select=id,name,plan,subscription_status,trial_ends_at,paid_until,created_at&order=created_at.desc'),
      api('/rest/v1/subscription_payments?select=id,clinic_id,amount,currency,method,reference,requested_plan,requested_days,status,notes,created_at,clinic:clinics(name)&status=eq.pending&order=created_at.asc'),
      api('/rest/v1/subscription_payments?select=id,clinic_id,amount,currency,method,reference,requested_plan,requested_days,status,notes,created_at,confirmed_at,clinic:clinics(name)&order=created_at.desc&limit=200'),
      api('/rest/v1/subscription_events?select=id,event_type,old_status,new_status,old_plan,new_plan,notes,created_at,clinic:clinics(name)&order=created_at.desc&limit=100'),
      api('/rest/v1/rpc/platform_admin_billing_summary',{method:'POST',body:{}})
    ]);
    clinics=Array.isArray(c)?c:[];
    pendingPayments=Array.isArray(p)?p:[];
    paymentHistory=Array.isArray(h)?h:[];
    events=Array.isArray(e)?e:[];
    summary=Array.isArray(s)&&s.length?s[0]:null;
    renderStats(); renderExpiry(); renderClinics(); renderPayments(); renderPaymentHistory(); renderEvents(); fillClinicSelect();
    msg($('globalMessage'),'تم التحديث','success');
  }catch(e){ msg($('globalMessage'),friendly(e),'error'); }
}

function expiringSoon(){
  const now=Date.now(), max=now+7*24*60*60*1000;
  return clinics.filter(c=>{
    if(c.subscription_status!=='active'||!c.paid_until) return false;
    const t=new Date(c.paid_until).getTime(); return Number.isFinite(t)&&t>=now&&t<=max;
  }).sort((a,b)=>new Date(a.paid_until)-new Date(b.paid_until));
}

function renderStats(){
  const fallbackCount=s=>clinics.filter(c=>c.subscription_status===s).length;
  const values=[
    ['كل العيادات',summary?.total_clinics??clinics.length],
    ['تجربة',summary?.trialing_clinics??fallbackCount('trialing')],
    ['نشط',summary?.active_clinics??fallbackCount('active')],
    ['موقوف/متأخر',summary?.attention_clinics??(fallbackCount('suspended')+fallbackCount('past_due'))],
    ['دفعات معلقة',summary?.pending_payments??pendingPayments.length],
    ['إيراد مؤكد',money(summary?.confirmed_revenue_sdg??0,'SDG')],
    ['تنتهي خلال 7 أيام',summary?.expiring_7d??expiringSoon().length]
  ];
  $('stats').innerHTML=values.map(([l,v])=>`<div class="stat"><span>${esc(l)}</span><strong>${esc(v)}</strong></div>`).join('');
}

function renderExpiry(){
  const rows=expiringSoon();
  $('expiryPanel').classList.toggle('hidden',rows.length===0);
  $('expiryBody').innerHTML=rows.map(c=>{
    const ms=Math.max(0,new Date(c.paid_until).getTime()-Date.now());
    const days=Math.ceil(ms/(24*60*60*1000));
    return `<tr><td><strong>${esc(c.name)}</strong></td><td>${esc(c.plan)}</td><td>${fmtDate(c.paid_until)}</td><td>${days} يوم</td></tr>`;
  }).join('');
}

function filteredClinics(){
  const q=$('clinicSearch').value.trim().toLowerCase(), s=$('clinicStatus').value;
  return clinics.filter(c=>(!q||String(c.name).toLowerCase().includes(q))&&(!s||c.subscription_status===s));
}

function renderClinics(){
  const rows=filteredClinics();
  $('clinicsBody').innerHTML=rows.length?rows.map(c=>`<tr>
    <td><strong>${esc(c.name)}</strong></td><td>${esc(c.plan)}</td>
    <td><span class="badge ${esc(c.subscription_status)}">${esc(statusLabel(c.subscription_status))}</span></td>
    <td>${fmtDate(c.trial_ends_at)}</td><td>${fmtDate(c.paid_until)}</td>
    <td><div class="actions">
      ${c.subscription_status!=='suspended'?`<button class="warn" data-clinic="${esc(c.id)}" data-status="suspended">إيقاف</button>`:''}
      ${c.subscription_status!=='active'&&c.paid_until&&new Date(c.paid_until).getTime()>=Date.now()?`<button class="secondary" data-clinic="${esc(c.id)}" data-status="active">تنشيط</button>`:''}
      ${c.subscription_status!=='cancelled'?`<button class="danger" data-clinic="${esc(c.id)}" data-status="cancelled">إلغاء</button>`:''}
    </div></td></tr>`).join(''):`<tr><td colspan="6" class="empty">لا توجد نتائج</td></tr>`;
  $('clinicsBody').querySelectorAll('[data-status]').forEach(b=>b.addEventListener('click',()=>changeStatus(b.dataset.clinic,b.dataset.status)));
}

function renderPayments(){
  $('paymentsBody').innerHTML=pendingPayments.length?pendingPayments.map(p=>`<tr>
    <td>${esc(p.clinic?.name||'—')}</td><td class="money">${money(p.amount,p.currency)}</td>
    <td>${esc(p.requested_plan)} / ${Number(p.requested_days||0).toLocaleString('ar-EG')} يوم</td><td>${esc(p.reference||'—')}</td>
    <td><div class="actions"><button class="secondary" data-confirm="${esc(p.id)}">تأكيد</button><button class="danger" data-reject="${esc(p.id)}">رفض</button></div></td>
  </tr>`).join(''):`<tr><td colspan="5" class="empty">لا توجد دفعات معلقة</td></tr>`;
  $('paymentsBody').querySelectorAll('[data-confirm]').forEach(b=>b.addEventListener('click',()=>confirmPayment(b.dataset.confirm)));
  $('paymentsBody').querySelectorAll('[data-reject]').forEach(b=>b.addEventListener('click',()=>rejectPayment(b.dataset.reject)));
}

function renderPaymentHistory(){
  const s=$('paymentStatus').value;
  const rows=paymentHistory.filter(p=>!s||p.status===s);
  $('paymentHistoryBody').innerHTML=rows.length?rows.map(p=>`<tr>
    <td>${fmtDate(p.confirmed_at||p.created_at)}</td><td>${esc(p.clinic?.name||'—')}</td><td class="money">${money(p.amount,p.currency)}</td>
    <td><span class="badge payment-${esc(p.status)}">${esc(paymentStatusLabel(p.status))}</span></td><td>${esc(p.method||'—')}</td>
    <td>${esc(p.requested_plan||'—')} / ${Number(p.requested_days||0).toLocaleString('ar-EG')} يوم</td><td>${esc(p.reference||'—')}</td>
  </tr>`).join(''):`<tr><td colspan="7" class="empty">لا توجد دفعات بهذه الحالة</td></tr>`;
}

function renderEvents(){
  $('eventsBody').innerHTML=events.length?events.map(e=>`<tr><td>${fmtDate(e.created_at)}</td><td>${esc(e.clinic?.name||'—')}</td><td>${esc(eventLabel(e.event_type))}</td><td>${esc(statusLabel(e.old_status))} → ${esc(statusLabel(e.new_status))}</td><td>${esc(e.notes||'—')}</td></tr>`).join(''):`<tr><td colspan="5" class="empty">لا يوجد سجل بعد</td></tr>`;
}

function fillClinicSelect(){
  $('manualClinic').innerHTML=clinics.length?clinics.map(c=>`<option value="${esc(c.id)}">${esc(c.name)} — ${esc(statusLabel(c.subscription_status))}</option>`).join(''):'<option value="">لا توجد عيادات</option>';
}

async function changeStatus(clinicId,status){
  if(!confirm(`تأكيد تغيير حالة العيادة إلى: ${statusLabel(status)}؟`)) return;
  try{ await api('/rest/v1/rpc/set_clinic_subscription_status',{method:'POST',body:{p_clinic_id:clinicId,p_status:status,p_notes:'Changed from platform admin'}}); await loadAll(); }
  catch(e){ msg($('globalMessage'),friendly(e),'error'); }
}

async function confirmPayment(id){
  if(!confirm('تأكيد استلام الدفعة وتفعيل/تمديد الاشتراك؟')) return;
  try{ await api('/rest/v1/rpc/confirm_subscription_payment',{method:'POST',body:{p_payment_id:id,p_plan:null,p_days:null}}); await loadAll(); }
  catch(e){ msg($('globalMessage'),friendly(e),'error'); }
}

async function rejectPayment(id){
  const notes=prompt('سبب الرفض أو الملاحظة:','')??'';
  try{ await api('/rest/v1/rpc/reject_subscription_payment',{method:'POST',body:{p_payment_id:id,p_notes:notes}}); await loadAll(); }
  catch(e){ msg($('globalMessage'),friendly(e),'error'); }
}

async function submitManualPayment(ev){
  ev.preventDefault();
  if(!$('manualClinic').value) return msg($('globalMessage'),'ما في عيادة مختارة','error');
  const body={
    p_clinic_id:$('manualClinic').value,
    p_amount:Number($('manualAmount').value),
    p_currency:$('manualCurrency').value.trim()||'SDG',
    p_method:$('manualMethod').value,
    p_reference:$('manualReference').value.trim(),
    p_plan:$('manualPlan').value,
    p_days:Number($('manualDays').value),
    p_notes:$('manualNotes').value.trim()
  };
  try{
    msg($('globalMessage'),'جاري تسجيل الدفعة…');
    await api('/rest/v1/rpc/admin_record_subscription_payment',{method:'POST',body});
    $('manualPaymentForm').reset(); $('manualCurrency').value='SDG'; $('manualDays').value='30';
    await loadAll(); msg($('globalMessage'),'تم تسجيل الدفعة وتفعيل الاشتراك','success');
  }catch(e){ msg($('globalMessage'),friendly(e),'error'); }
}

function friendly(e){
  const m=String(e?.message||e||'');
  if(m.includes('Invalid login credentials')) return 'البريد أو كلمة المرور غير صحيحة';
  if(m.includes('Email not confirmed')) return 'أكدي البريد الإلكتروني أولًا';
  if(m.includes('not_platform_admin')) return 'الحساب غير مخوّل كإدارة منصة';
  if(m.includes('invalid_setup_code')) return 'رمز تهيئة المدير غير صحيح';
  if(m.includes('platform_admin_already_configured')) return 'تم تجهيز أول حساب إدارة مسبقًا؛ لا يمكن استخدام رمز التهيئة مرة أخرى';
  if(m.includes('bootstrap_not_available')) return 'رمز تهيئة أول مدير غير متاح أو تم استخدامه';
  if(m.includes('active_requires_payment')) return 'لا يمكن تنشيط العيادة بدون اشتراك مدفوع ساري';
  if(m.includes('trial_expired')) return 'الفترة التجريبية انتهت';
  if(m.includes('payment_already_processed')||m.includes('payment_not_pending')) return 'تمت معالجة الدفعة مسبقًا';
  if(m.includes('Failed to fetch')) return 'تعذر الاتصال بالشبكة';
  return m||'حدث خطأ غير متوقع';
}

$('loginBtn').addEventListener('click',signIn);
$('signupBtn').addEventListener('click',signUp);
$('claimAdminBtn').addEventListener('click',claimInitialAdmin);
$('logoutBtn').addEventListener('click',()=>{clearSession();location.reload()});
$('noAccessLogout').addEventListener('click',()=>{clearSession();location.reload()});
$('refreshBtn').addEventListener('click',loadAll);
$('clinicSearch').addEventListener('input',renderClinics);
$('clinicStatus').addEventListener('change',renderClinics);
$('paymentStatus').addEventListener('change',renderPaymentHistory);
$('manualPaymentForm').addEventListener('submit',submitManualPayment);

if(session?.access_token) enterAdmin();
