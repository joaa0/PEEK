"use client";
import { useEffect, useRef, useState } from "react";
import { describeError } from "./api";

export function useApi<T>(key: string, loader: () => Promise<T>) {
  const load = useRef(loader);
  load.current = loader;
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<{
    data?: T;
    error?: string;
    loading: boolean;
  }>({ loading: true });
  useEffect(() => {
    let active = true;
    setState({ loading: true });
    load
      .current()
      .then((data) => {
        if (active) setState({ data, loading: false });
      })
      .catch((error) => {
        if (active) setState({ error: describeError(error), loading: false });
      });
    return () => {
      active = false;
    };
  }, [key, revision]);
  return { ...state, reload: () => setRevision((value) => value + 1) };
}
