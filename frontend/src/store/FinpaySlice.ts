import { configureStore, createSlice, type PayloadAction } from "@reduxjs/toolkit";
import type { Notification, Payment, PendingAttempt, Session, SpendingReport, Summary, Wallet } from "@/lib/ApiSchemas";

export interface IFinpayState {
  session: Session | null;
  wallet: Wallet | null;
  payments: Payment[];
  notifications: Notification[];
  report: SpendingReport | null;
  summary: Summary | null;
  pending: PendingAttempt | null;
  activePayment: Payment | null;
  phase: "loading" | "guest" | "ready";
  busy: boolean;
  error: string | null;
  offset: number;
}
const initialState: IFinpayState = { session: null, wallet: null, payments: [], notifications: [], report: null,
  summary: null, pending: null, activePayment: null, phase: "loading", busy: false, error: null, offset: 0 };

const slice = createSlice({
  name: "finpay", initialState,
  reducers: {
    update(state, action: PayloadAction<Partial<IFinpayState>>) { Object.assign(state, action.payload); },
    reset() { return initialState; },
  },
});

export const { update, reset } = slice.actions;
export const makeStore = () => configureStore({ reducer: { finpay: slice.reducer } });
export type FinpayStore = ReturnType<typeof makeStore>;
export type RootState = ReturnType<FinpayStore["getState"]>;
export type AppDispatch = FinpayStore["dispatch"];
export const selectFinpay = (state: RootState) => state.finpay;
