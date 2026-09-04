/**
 * Typed wrappers around every backend endpoint — the frontend's single source
 * of "what the API can do". Pages import these functions, never axios
 * directly, so the HTTP details live in exactly one file.
 */
import { api } from './client';
import type {
  AdvanceResponse,
  HistoryEntry,
  QueueStats,
  BookingConfig,
  CreateReservationRequest,
  DayAvailability,
  GeocodeResult,
  GoogleLoginResponse,
  JoinResponse,
  LeaveNowResponse,
  LoginResponse,
  ReservationResponse,
  VenueConfig,
  MeResponse,
  NearbyPlaceResponse,
  NotifyChannel,
  PositionResponse,
  QueueResponse,
  RestaurantDetail,
  SignupRequest,
  VenueDetail,
  VenueSummary,
  WaitingEntry,
} from './types';

// ---- auth ----

export const signup = (body: SignupRequest) =>
  api.post('/auth/signup', body).then((r) => r.data);

export const login = (email: string, password: string) =>
  api.post<LoginResponse>('/auth/login', { email, password }).then((r) => r.data);

/**
 * Exchange Google's ID token for one of OUR JWTs (FR-1). businessName is
 * only used if this is the first time we've seen this Google account.
 */
export const googleSignIn = (idToken: string, businessName?: string) =>
  api.post<GoogleLoginResponse>('/auth/google', { idToken, businessName }).then((r) => r.data);

export const fetchMe = () => api.get<MeResponse>('/me').then((r) => r.data);

// ---- owner/staff (JWT attached by the interceptor) ----

export const listQueues = () => api.get<QueueResponse[]>('/queues').then((r) => r.data);

export const createQueue = (name: string, stationCount?: number, defaultServiceMinutes?: number) =>
  api.post<QueueResponse>('/queues', { name, stationCount, defaultServiceMinutes }).then((r) => r.data);

export const getQueue = (id: number) =>
  api.get<QueueResponse>(`/queues/${id}`).then((r) => r.data);

export const getWaitingEntries = (queueId: number) =>
  api.get<WaitingEntry[]>(`/queues/${queueId}/entries`, { params: { status: 'WAITING' } }).then((r) => r.data);

/** P0: open/close, explicit and audited. */
export const setQueueStatus = (queueId: number, status: 'OPEN' | 'CLOSED', reason?: string) =>
  api.patch<QueueResponse>(`/queues/${queueId}/status`, { status, reason }).then((r) => r.data);

/** P1: completed entries for a day. */
export const getHistory = (queueId: number, status: string, date?: string) =>
  api
    .get<HistoryEntry[]>(`/queues/${queueId}/history`, { params: { status, date } })
    .then((r) => r.data);

/** P1: the operational day summary. */
export const getQueueStats = (queueId: number) =>
  api.get<QueueStats>(`/queues/${queueId}/stats`).then((r) => r.data);

/** Owner view of today's bookings (read-only; customers check in via their link). */
export const getReservationsForDay = (queueId: number, date: string) =>
  api
    .get<{ id: number; slotStart: string; customerName: string; partySize: number; status: string }[]>(
      `/queues/${queueId}/reservations`,
      { params: { date } },
    )
    .then((r) => r.data);

/** P1: the customer bows out of the line. */
export const leaveQueue = (entryToken: string) => api.delete(`/public/entries/${entryToken}`);

/**
 * V9: revise how we reach you, from the ticket page. PATCH because it
 * revises one aspect of the ticket rather than replacing it.
 */
export const setNotifyPreference = (
  entryToken: string,
  channel: NotifyChannel,
  phoneNumber?: string,
) => api.patch(`/public/entries/${entryToken}/notify-preference`, { channel, phoneNumber });

/** V8: post-service feedback — 1–5 stars + optional comment. */
export const sendFeedback = (entryToken: string, stars: number, comment?: string) =>
  api.post(`/public/entries/${entryToken}/feedback`, { stars, comment });

export const advanceQueue = (queueId: number) =>
  api.post<AdvanceResponse>(`/queues/${queueId}/advance`).then((r) => r.data);

export const markNoShow = (entryId: number) => api.delete(`/entries/${entryId}`);

// ---- public (no auth; the tokens ARE the authorization) ----

/**
 * V9 widened the body. Everything past partySize is optional, and the caller
 * decides what to send — the join FORM only ever collects a name and a party
 * size; location arrives only if the server asked for it (428), and the
 * notification choice is offered after they're already in the line.
 */
export interface JoinOptions {
  /** Sent only to satisfy a remote-eligibility check. Never stored. */
  latitude?: number;
  longitude?: number;
  /** True ONLY when joining from the discovery page — applies the venue's
   *  distance limit. Absent means "I'm here", which is what a scanned QR
   *  code sends and what every printed poster relies on. */
  remote?: boolean;
  phoneNumber?: string;
  notifyChannel?: NotifyChannel;
}

export const joinQueue = (
  joinToken: string,
  customerName: string,
  partySize: number,
  idempotencyKey?: string,
  options: JoinOptions = {},
) =>
  api
    .post<JoinResponse>(
      `/public/queues/${joinToken}/entries`,
      { customerName, partySize, ...options },
      idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
    )
    .then((r) => r.data);

// ---- V9: public discovery (no auth, no account) ----

/** Search restaurants. lat/lng are optional and only used to sort by distance. */
export const searchVenues = (q?: string, lat?: number, lng?: number) =>
  api
    .get<VenueSummary[]>('/public/venues', { params: { q: q || undefined, lat, lng } })
    .then((r) => r.data);

/** One restaurant — reached from a search result OR from a scanned QR code. */
export const getVenue = (joinToken: string, lat?: number, lng?: number) =>
  api.get<VenueDetail>(`/public/venues/${joinToken}`, { params: { lat, lng } }).then((r) => r.data);

/**
 * "What's near me?" — NowServing venues merged with OpenStreetMap points of
 * interest, computed server-side (never call Overpass from the browser: it's
 * a shared, unauthenticated public service with no SLA). Requires a
 * location; the caller must have one before calling this.
 */
export const getNearbyVenues = (lat: number, lng: number, radiusMiles = 3) =>
  api
    .get<NearbyPlaceResponse[]>('/public/venues/nearby', { params: { lat, lon: lng, radiusMiles } })
    .then((r) => r.data);

/**
 * Resolve a scanned RESTAURANT QR. Permanent per business — unlike getVenue,
 * whose token dies with its queue.
 */
export const getRestaurant = (publicToken: string, lat?: number, lng?: number) =>
  api
    .get<RestaurantDetail>(`/public/restaurants/${publicToken}`, { params: { lat, lng } })
    .then((r) => r.data);

export const getPosition = (entryToken: string) =>
  api.get<PositionResponse>(`/public/entries/${entryToken}`).then((r) => r.data);

// ---- reservations (FR-10) ----

export const getAvailability = (joinToken: string, date: string) =>
  api
    .get<DayAvailability>(`/public/queues/${joinToken}/availability`, { params: { date } })
    .then((r) => r.data);

/**
 * The Idempotency-Key header is what makes a double-tap or a retry safe: the
 * server returns the ORIGINAL booking instead of consuming a second seat.
 */
export const bookSlot = (joinToken: string, body: CreateReservationRequest, idempotencyKey: string) =>
  api
    .post<ReservationResponse>(`/public/queues/${joinToken}/reservations`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })
    .then((r) => r.data);

export const getReservation = (reservationToken: string) =>
  api.get<ReservationResponse>(`/public/reservations/${reservationToken}`).then((r) => r.data);

export const checkInReservation = (reservationToken: string) =>
  api.post<JoinResponse>(`/public/reservations/${reservationToken}/check-in`).then((r) => r.data);

export const cancelReservation = (reservationToken: string) =>
  api.delete(`/public/reservations/${reservationToken}`);

export const getBookingConfig = (queueId: number) =>
  api.get<BookingConfig>(`/queues/${queueId}/booking-config`).then((r) => r.data);

export const saveBookingConfig = (queueId: number, config: Partial<BookingConfig>) =>
  api.put<BookingConfig>(`/queues/${queueId}/booking-config`, config).then((r) => r.data);

// ---- Leave-Now (FR-11..FR-16) ----

/** Opt in. The backend immediately coarsens this to ~1km and stores it in Redis with a TTL. */
export const shareLocation = (entryToken: string, latitude: number, longitude: number) =>
  api.post(`/public/entries/${entryToken}/location`, { latitude, longitude });

export const revokeLocation = (entryToken: string) =>
  api.delete(`/public/entries/${entryToken}/location`);

export const getLeaveNow = (entryToken: string) =>
  api.get<LeaveNowResponse>(`/public/entries/${entryToken}/leave-now`).then((r) => r.data);

export const sendOnMyWay = (entryToken: string) =>
  api.post(`/public/entries/${entryToken}/on-my-way`);

export const holdSpot = (entryToken: string) =>
  api.post<{ graceExpiresAt: string }>(`/public/entries/${entryToken}/hold`).then((r) => r.data);

/** Owner-side address search (TomTom). [] when TomTom isn't configured. */
export const searchAddress = (q: string) =>
  api.get<GeocodeResult[]>('/geo/search', { params: { q } }).then((r) => r.data);

export const getVenueConfig = (queueId: number) =>
  api.get<VenueConfig>(`/queues/${queueId}/venue-config`).then((r) => r.data);

export const saveVenueConfig = (queueId: number, config: Partial<VenueConfig>) =>
  api.put<VenueConfig>(`/queues/${queueId}/venue-config`, config).then((r) => r.data);

/**
 * What this deployment can actually deliver. Asked BEFORE offering an alert
 * channel, so a server with no Twilio credentials never shows "Text me
 * instead" — a button whose only outcome would be a silent failure.
 */
export const getNotificationCapabilities = () =>
  api
    .get<{ pushAvailable: boolean; smsAvailable: boolean }>('/public/notifications/capabilities')
    .then((r) => r.data);

/** The server's VAPID public key — fetched, never hard-coded, so the two can't drift. */
export const getVapidKey = () =>
  api.get<{ publicKey: string }>('/public/push/vapid-key').then((r) => r.data);

/** Hands the browser's own subscription object straight to the backend (FR-8). */
export const savePushSubscription = (entryToken: string, subscription: unknown) =>
  api.post(`/public/entries/${entryToken}/push-subscription`, subscription);

// ---- owner settings (BusinessController) ----

/** Rename my restaurant. businessId comes from the JWT server-side — there is
 *  deliberately no id in this call to get wrong. */
export const renameBusiness = (name: string) =>
  api.patch<{ id: number; name: string }>('/business', { name }).then((r) => r.data);

/** Permanently delete my restaurant and everything it owns. */
export const deleteBusiness = () => api.delete('/business');
