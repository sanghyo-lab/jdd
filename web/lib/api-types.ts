export type ApiError = { code: string; message: string; retryable: boolean };
export type TicketStatus = "OPEN" | "IN_PROGRESS" | "RESOLVED";
export type Context = Partial<Record<"customerId" | "orderId" | "productId" | "requestId" | "checkoutKey" | "occurredAt", string | null>>;
export type Ticket = { ticketId: string; version: number; title: string; message: string; context: Context;
  assigneeId: string | null; status: TicketStatus; createdAt: string; updatedAt: string };
export type Assignee = { id: string; displayName: string };
export type AnalysisSummary = { analysisRequestId: string; ticketVersion: number; submissionStatus: string;
  investigationId: string | null; investigationStatus: string | null; createdAt: string; updatedAt: string };
export type TicketDetail = { ticket: Ticket; analyses: AnalysisSummary[] };
