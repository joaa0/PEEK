import type { Command, CommandStatus } from "./contracts";
import type { FiscalRecord } from "./fiscal";

const requestedAt = "2026-01-01T12:00:00Z";
const deadlineAt = "2026-01-01T12:05:00Z";
function example(status: CommandStatus, index: number): FiscalRecord {
  const confirmed = status === "CONFIRMED";
  const failed = status === "FAILED";
  const command: Command = {
    id: `EXEMPLO-FISCAL-${index}`,
    kind: "FISCAL",
    productId: `EXEMPLO-PRODUTO-${index}`,
    mappingId: `EXEMPLO-MAPPING-${index}`,
    channel: "Sistema fiscal simulado",
    status,
    requestedAt,
    deadlineAt,
    confirmedAt: confirmed ? "2026-01-01T12:02:10Z" : null,
    confirmationOccurredAt: confirmed ? "2026-01-01T12:02:00Z" : null,
    confirmationEventId: confirmed ? `EXEMPLO-EVENTO-${index}` : null,
    externalDocumentId: confirmed ? "DOC-DEMO-1843" : null,
    lastErrorCode: failed
      ? "ADAPTER_UNAVAILABLE"
      : status === "TIMED_OUT"
        ? "CONFIRMATION_TIMEOUT"
        : null,
    expectedStock: null,
    requestedQuantity: 5,
    attempts: [
      {
        id: `EXEMPLO-TENTATIVA-${index}`,
        attemptNumber: 1,
        idempotencyKey: `EXEMPLO-CHAVE-${index}`,
        dispatchedAt: requestedAt,
        respondedAt: "2026-01-01T12:00:01Z",
        result: failed ? "FAILED" : "ACCEPTED",
        externalRequestId: failed ? null : `EXEMPLO-ENVIO-${index}`,
        errorCode: failed ? "ADAPTER_UNAVAILABLE" : null,
        errorMessage: failed ? "Sistema fiscal simulado indisponível." : null,
      },
    ],
  };
  return {
    id: command.id,
    orderId: String(1841 + index),
    sku: "CAM-001",
    command,
    exceptionId: null,
  };
}

// Illustrative rows have no real IDs and must never link to investigations.
export const fiscalExamples = [
  example("PENDING_CONFIRMATION", 1),
  example("CONFIRMED", 2),
  example("FAILED", 3),
  example("TIMED_OUT", 4),
];
