/**
 * Sprint 2: subscribe to one STOMP topic over a WebSocket.
 *
 * usePolling asks "anything new?" every 10 seconds; this hook keeps a
 * persistent socket open and the SERVER speaks the instant something
 * changes. Pages keep polling as a slow fallback (network blips, server
 * restarts), so realtime is an upgrade, not a dependency — if the socket
 * dies, the page silently degrades to Sprint 1 behaviour.
 *
 * Returns `live` so pages can show a "LIVE" indicator when connected.
 */
import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { API_URL } from '../api/client';

// http://localhost:8080  ->  ws://localhost:8080/ws  (wss:// behind https)
const WS_URL = API_URL.replace(/^http/, 'ws') + '/ws';

export function useLiveTopic<T>(topic: string, onMessage: (payload: T) => void) {
  const [live, setLive] = useState(false);
  // Ref, not state: the callback changes every render, but we don't want to
  // tear down the socket each time — the subscription reads the latest via ref.
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  useEffect(() => {
    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 3000, // auto-reconnect: sockets die on wifi changes, laptops sleeping...
      onConnect: () => {
        setLive(true);
        client.subscribe(topic, (frame) => {
          onMessageRef.current(JSON.parse(frame.body) as T);
        });
      },
      onWebSocketClose: () => setLive(false),
    });
    client.activate();

    // Cleanup on unmount — otherwise every visited page leaves a zombie
    // socket behind (the same leak class as an uncleared interval).
    return () => {
      client.deactivate();
      setLive(false);
    };
  }, [topic]);

  return { live };
}
