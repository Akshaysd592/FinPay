"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";
import { useDispatch, useSelector } from "react-redux";
import { selectFinpay, update, type AppDispatch } from "@/store/FinpaySlice";
import type { PaymentRequest, Session } from "@/lib/ApiSchemas";
import { restoreAttempt } from "@/lib/PaymentUtils";
import * as api from "@/services/finpayService";

function attemptKey(userId: string) { return `finpay:attempt:${userId}`; }
function errorCode(error: unknown) { return error instanceof api.ApiError ? error.code : "unavailable"; }

export function useDashboard() {
  const state = useSelector(selectFinpay);
  const dispatch = useDispatch<AppDispatch>();
  const submitting = useRef(false);
  const walletId = state.wallet?.id;
  const month = useMemo(() => new Date().toISOString().slice(0, 7), []);
  const fail = useCallback((error: unknown) => {
    if (error instanceof api.ApiError && error.status === 401) {
      // The per-user retry key remains in sessionStorage across reauthentication.
      dispatch(update({ session: null, phase: "guest", wallet: null, payments: [], notifications: [],
        report: null, summary: null, pending: null, activePayment: null, error: "sessionExpired" }));
    } else dispatch(update({ error: errorCode(error) }));
  }, [dispatch]);

  const loadViews = useCallback(async (session: Session, walletId?: string, offset = 0) => {
    const results = await Promise.allSettled([
      walletId ? api.getWallet(walletId) : api.createWallet(session), api.getPayments(offset), api.getNotifications(), api.getReport(month),
    ]);
    if (results[0].status === "fulfilled") dispatch(update({ wallet: results[0].value })); else fail(results[0].reason);
    if (results[1].status === "fulfilled") dispatch(update({ payments: results[1].value, offset })); else fail(results[1].reason);
    if (results[2].status === "fulfilled") dispatch(update({ notifications: results[2].value })); else fail(results[2].reason);
    if (results[3].status === "fulfilled") dispatch(update({ report: results[3].value })); else fail(results[3].reason);
  }, [dispatch, fail, month]);

  useEffect(() => {
    let active = true;
    async function initialize() {
      try {
        const session = await api.getSession();
        if (!active) return;
        if (!session) {
          const loginFailed = new URL(window.location.href).searchParams.get("auth") === "unavailable";
          dispatch(update({ phase: "guest", ...(loginFailed ? { error: "unavailable" } : {}) }));
          return;
        }
        let pending = null;
        try { pending = restoreAttempt(sessionStorage.getItem(attemptKey(session.userId))); } catch { /* Submission stays disabled if storage later fails. */ }
        dispatch(update({ session, pending, phase: "ready" }));
        await loadViews(session);
      } catch (error) { if (active) { fail(error); dispatch(update({ phase: "guest" })); } }
    }
    void initialize();
    return () => { active = false; };
  }, [dispatch, fail, loadViews]);

  const refresh = useCallback(async () => {
    if (!state.session) return;
    dispatch(update({ error: null }));
    await loadViews(state.session, walletId, state.offset);
  }, [dispatch, loadViews, state.session, walletId, state.offset]);

  const submit = useCallback(async (request?: PaymentRequest) => {
    if (!state.session || state.busy || submitting.current) return;
    const attempt = state.pending ?? (request ? { key: crypto.randomUUID(), request } : null);
    if (!attempt) return;
    submitting.current = true;
    try { sessionStorage.setItem(attemptKey(state.session.userId), JSON.stringify(attempt)); }
    catch { submitting.current = false; dispatch(update({ error: "storageUnavailable" })); return; }
    dispatch(update({ pending: attempt, activePayment: null, busy: true, error: null }));
    try {
      const payment = await api.createPayment(state.session, attempt.key, attempt.request);
      dispatch(update({ activePayment: payment }));
      if (payment.status !== "PENDING") {
        sessionStorage.removeItem(attemptKey(state.session.userId));
        dispatch(update({ pending: null }));
      }
      await loadViews(state.session, walletId, 0);
    } catch (error) { fail(error); }
    finally { submitting.current = false; dispatch(update({ busy: false })); }
  }, [dispatch, fail, loadViews, state.session, state.busy, state.pending, walletId]);

  useEffect(() => {
    if (state.activePayment?.status !== "PENDING" || !state.session) return;
    const id = state.activePayment.id;
    const session = state.session;
    let active = true;
    let checking = false;
    // Short polling reports actual settlement; expiry or timeout never fabricates a failed payment.
    const timer = setInterval(async () => {
      if (checking) return;
      checking = true;
      try {
        const payment = await api.getPayment(id);
        if (!active) return;
        dispatch(update({ activePayment: payment }));
        if (payment.status !== "PENDING") {
          sessionStorage.removeItem(attemptKey(session.userId));
          dispatch(update({ pending: null }));
          await loadViews(session, walletId, 0);
        }
      } catch (error) { if (active) fail(error); }
      finally { checking = false; }
    }, 2000);
    return () => { active = false; clearInterval(timer); };
  }, [dispatch, fail, loadViews, state.activePayment?.id, state.activePayment?.status, state.session, walletId]);

  const summarize = useCallback(async () => {
    if (!state.session) return;
    dispatch(update({ busy: true, error: null }));
    try { dispatch(update({ summary: await api.getSummary(state.session, month) })); }
    catch (error) { fail(error); }
    finally { dispatch(update({ busy: false })); }
  }, [dispatch, fail, month, state.session]);

  const signOut = useCallback(async () => {
    if (!state.session) return;
    try { window.location.assign((await api.logout(state.session)).redirect); } catch (error) { fail(error); }
  }, [fail, state.session]);

  const changePage = useCallback(async (offset: number) => {
    if (!state.session) return;
    await loadViews(state.session, walletId, offset);
  }, [loadViews, state.session, walletId]);

  return { state, month, refresh, submit, summarize, signOut, changePage };
}

