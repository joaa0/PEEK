export type ExceptionCode = "E01" | "E02" | "E03" | "E04";
export type ExceptionStatus = "OPEN" | "RESOLVED";
export type DemoScenario = "Normal" | ExceptionCode;
export interface DemoReset {
  runId: string;
  totalDeleted: number;
}
export interface Evaluation {
  asOf: string;
  created: number;
  alreadyPresent: number;
  exceptionIds: string[];
  issues: { triggerEventId: string; code: string; field: string }[];
}
export type MappingStatus = "PENDING" | "ACTIVE" | "INACTIVE";
export interface ProductInput {
  sku: string;
  name: string;
  description: string | null;
  price: number | null;
  gtin: string | null;
  category: string | null;
}
export interface Product extends ProductInput {
  id: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}
export interface MappingInput {
  channel: string;
  externalId: string | null;
  status: MappingStatus;
}
export interface Mapping extends MappingInput {
  id: string;
  productId: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}
export interface Evidence {
  id: string;
  eventId: string | null;
  type: string;
  source: string;
  label: string;
  value: string;
  occurredAt: string | null;
}
export interface JevAnalysis {
  summary: string;
  hypothesis: string;
  confidence: string;
  evidenceIds: string[];
  impact: string;
  recommendedAction: string;
  alternatives: string[];
}
export interface JevHypothesis {
  code:
    | "COUNT_REQUIRES_VERIFICATION"
    | "MOVEMENT_RECORDING_GAP"
    | "RECEIPT_RECORDING_GAP"
    | "ADJUSTMENT_REQUIRES_REVIEW"
    | "UNEXPLAINED_DIVERGENCE";
  statement: string;
  rationale: string;
  confidence: {
    value: number;
    meaning: "MODEL_SELF_REPORTED_RANKING" | "TYPESAFE_CHOICE_PROBABILITY";
  };
  evidenceIds: string[];
}
export interface JevInterpretation {
  contractVersion: "1.0";
  status: "AVAILABLE" | "FALLBACK";
  nature: "HYPOTHESIS_NOT_FACT" | "NO_MODEL_ANALYSIS";
  summary: string;
  mainHypothesis: JevHypothesis | null;
  alternatives: JevHypothesis[];
  impact: string | null;
  recommendedAction: string;
  fallbackReason: string | null;
  evaluation?: {
    provider: "TYPESAFE";
    model: string;
    confidence: number;
    probabilities: Partial<Record<JevHypothesis["code"], number>>;
    explanationSource: "PEEK_EVIDENCE_TEMPLATES";
  };
}
export interface Alert {
  id: string;
  code: ExceptionCode;
  triggerEventId: string;
  operationCommandId: string | null;
  status: ExceptionStatus;
  severity: "CRITICAL" | "WARNING" | "INFO";
  title: string;
  detectedAt: string;
  productId: string | null;
  sku: string | null;
  orderId: string | null;
  expectedState: string;
  observedState: string;
  ruleParameter: string;
  impact: string | null;
  recommendation: string;
  resolutionNote: string | null;
  resolvedAt: string | null;
  version: number;
  reconciliationEventId?: string | null;
  reconciledAt?: string | null;
  evidence: Evidence[];
  jev?: JevAnalysis | null;
}
export type EventType =
  | "SALE_CONFIRMED"
  | "STOCK_UPDATED"
  | "INVOICE_ISSUED"
  | "GOODS_RECEIVED"
  | "PHYSICAL_COUNT"
  | "STOCK_ADJUSTED"
  | "PHYSICAL_EXIT";
export interface CanonicalEvent {
  id: string;
  source: string;
  externalEventId: string;
  type: EventType;
  occurredAt: string;
  receivedAt: string;
  productId: string | null;
  sku: string | null;
  externalProductId: string | null;
  orderId: string | null;
  invoiceId: string | null;
  receiptId: string | null;
  movementId: string | null;
  quantity: number | null;
  stockAfter: number | null;
  confirmed: boolean;
  metadata: Record<string, string>;
}
export interface Stock {
  sku: string;
  expectedStock: number | null;
  systemStock: number | null;
  physicalStock: number | null;
  initialBaselineEventId: string | null;
  baselineEventId: string | null;
  checkpointEventId: string | null;
  systemUpdatedAt: string | null;
  physicalCountedAt: string | null;
  usedEventIds: string[];
}
export type CommandStatus =
  | "REQUESTED"
  | "PENDING_CONFIRMATION"
  | "FAILED"
  | "TIMED_OUT"
  | "CONFIRMED";
export interface Attempt {
  id: string;
  attemptNumber: number;
  idempotencyKey: string;
  dispatchedAt: string;
  respondedAt: string;
  result: string;
  externalRequestId?: string | null;
  externalId?: string | null;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface Execution {
  commandId: string | null;
  channel: string | null;
  status: CommandStatus | "NOT_AVAILABLE";
  requestedAt: string | null;
  deadlineAt: string | null;
  lastAttemptAt: string | null;
  confirmedAt: string | null;
  attempts: Attempt[];
}
export interface Command {
  id: string;
  kind: "INVENTORY_SYNC" | "FISCAL";
  productId: string;
  mappingId: string;
  channel: string;
  status: CommandStatus;
  requestedAt: string;
  deadlineAt: string;
  confirmedAt: string | null;
  confirmationOccurredAt: string | null;
  confirmationEventId: string | null;
  externalDocumentId: string | null;
  lastErrorCode: string | null;
  expectedStock: number | null;
  requestedQuantity: number;
  attempts: Attempt[];
}
export interface OperationalContext {
  product: Product;
  mappings: Mapping[];
  stock: Stock;
  inventorySync: Execution;
  fiscalOrchestration: Execution;
  exceptions: Alert[];
  fiscal: {
    status: string;
    documentId: string | null;
    source: string | null;
    confirmedAt: string | null;
    documentOccurredAt: string | null;
    evidenceEventId: string | null;
    commandId: string | null;
  };
}
export interface Investigation {
  exception: Alert;
  trigger: CanonicalEvent;
  order: CanonicalEvent | null;
  stockAtDetection: Stock;
  currentStock: Stock;
  systemSnapshot: CanonicalEvent | null;
  physicalCheckpoint: CanonicalEvent | null;
  jev?: JevInterpretation | null;
}
export type Destination = "ERP" | "MERCADO_LIVRE" | "SHOPEE";
export interface Propagation {
  id: string;
  productId: string;
  mappingId: string;
  channel: Destination;
  operation: string;
  idempotencyKey: string;
  productVersion: number;
  requestedAt: string;
  status: "REQUESTED" | "SUCCEEDED" | "FAILED";
  completedAt: string | null;
  externalId: string | null;
  attempts: Attempt[];
  reconciliationEventId?: string | null;
  reconciledAt?: string | null;
  evidence: Evidence[];
}
