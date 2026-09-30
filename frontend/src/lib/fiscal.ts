import { api, describeError } from "./api";
import type { Command } from "./contracts";

export interface FiscalRecord {
  id: string;
  orderId: string | null;
  sku: string | null;
  command: Command | null;
  exceptionId: string | null;
  error?: string;
}

// This endpoint lists E03 exceptions, not the entire external fiscal ledger.
// No fiscal status is inferred from an exception's OPEN/RESOLVED lifecycle.
export async function loadFiscalRecords(): Promise<FiscalRecord[]> {
  const alerts = await api.alerts(undefined, "E03");
  const commands = new Map<string, Promise<Command>>();
  return Promise.all(
    alerts.map(async (alert) => {
      const record: FiscalRecord = {
        id: alert.id,
        orderId: alert.orderId,
        sku: alert.sku,
        command: null,
        exceptionId: alert.id,
      };
      if (!alert.operationCommandId) return record;
      try {
        let request = commands.get(alert.operationCommandId);
        if (!request) {
          request = api.command(alert.operationCommandId);
          commands.set(alert.operationCommandId, request);
        }
        const command = await request;
        if (
          command.kind !== "FISCAL" ||
          command.id !== alert.operationCommandId
        ) {
          return {
            ...record,
            error:
              "O comando retornado não corresponde à orquestração fiscal desta exceção.",
          };
        }
        return { ...record, command };
      } catch (error) {
        return { ...record, error: describeError(error) };
      }
    }),
  );
}
