/**
 * TypeScript mirrors of the backend DTOs (the API contract).
 *
 * These are hand-written twins of the Java records in
 * nowserving-backend/src/main/java/com/nowserving/dto/. If the backend
 * contract changes, change it HERE too — TypeScript can't see Java.
 * (Teams automate this with OpenAPI codegen; hand-written is fine at our size
 * and forces you to actually know your contract.)
 */

// ---- auth (AuthDtos.java) ----

export interface SignupRequest {
  businessName: string;
  email: string;
  password: string;
  displayName: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string; // ISO instant
}

/** POST /auth/google — same token as a password login, plus a first-time flag. */
export interface GoogleLoginResponse extends LoginResponse {
  newAccount: boolean;
}

export interface MeResponse {
  ownerId: number;
  email: string;
  displayName: string;
  business: { id: number; name: string; publicToken: string };
}

// ---- queues (QueueDtos.java) ----

export type QueueStatus = 'OPEN' | 'CLOSED';

export interface QueueResponse {
  id: number;
  name: string;
  status: QueueStatus;
  stationCount: number;
  defaultServiceMinutes: number;
  joinToken: string;
  joinUrl: string;
  waitingCount: number;
  /** First few waiting names, queue order — so a dashboard listing several
   *  lines shows WHO is in each, not just how many. */
  waitingNames: string[];
  /** Public discovery visibility. With `status`, distinguishes "shut for the
   *  night" (CLOSED, still listed) from "retired" (CLOSED and unlisted). */
  listedPublicly: boolean;
  createdAt: string;
}

export interface WaitingEntry {
  entryId: number;
  customerName: string;
  partySize: number;
  position: number;
  joinedAt: string;
  waitedMinutes: number;
  /** FR-15: the customer tapped "I'm on my way". Sent by the backend since
   *  Sprint 5 — these two fields were simply missing from this mirror, which
   *  is why the dashboard never showed them. */
  enRoute: boolean;
  /** FR-16: their spot is being held until this moment. */
  graceExpiresAt: string | null;
}

export interface AdvanceResponse {
  served: { entryId: number; customerName: string; partySize: number; servedAt: string };
  nextUp: WaitingEntry | null;
}

// ---- public (PublicDtos.java) ----

export type EntryStatus = 'WAITING' | 'CALLED' | 'SERVED' | 'NO_SHOW' | 'LEFT';

export interface JoinResponse {
  entryToken: string;
  queueName: string;
  position: number;
  peopleAhead: number;
  estimatedMinutes: number;
}

export interface HistoryEntry {
  entryId: number;
  customerName: string;
  partySize: number;
  status: EntryStatus;
  joinedAt: string;
  servedAt: string | null;
  waitedMinutes: number | null;
  ratingStars: number | null;
  feedbackComment: string | null;
}

export interface QueueStats {
  status: QueueStatus;
  waitingCount: number;
  servedToday: number;
  avgWaitMinutesToday: number | null;
  medianWaitMinutesToday: number | null;
  longestCurrentWaitMinutes: number | null;
  reservationsToday: number;
  noShowsToday: number;
  paceMinutesPerCustomer: number | null;
}

export interface PositionResponse {
  status: EntryStatus;
  position: number | null;
  peopleAhead: number | null;
  estimatedMinutes: number | null;
  /** Set once SERVED — lets the ticket page close the story properly. */
  servedAt: string | null;
  waitedMinutes: number | null;
  /** V9: which restaurant this ticket is for. Sent on both transports. */
  businessName: string | null;
}

// ---- reservations (ReservationDtos.java) ----

export type ReservationStatus = 'BOOKED' | 'REDEEMED' | 'CANCELLED';

export interface SlotResponse {
  startTime: string; // ISO instant
  durationMinutes: number;
  capacity: number;
  booked: number;
  available: boolean;
}

export interface DayAvailability {
  dayStart: string;
  slots: SlotResponse[];
}

export interface CreateReservationRequest {
  customerName: string;
  contact?: string;
  partySize?: number;
  slotStart: string;
}

export interface ReservationResponse {
  reservationToken: string;
  /** Which restaurant — the queue name alone ("Dinner Service") doesn't
   *  tell someone who booked from a list where to actually go. */
  businessName: string;
  queueName: string;
  slotStart: string;
  durationMinutes: number;
  customerName: string;
  partySize: number;
  status: ReservationStatus;
}

export interface BookingConfig {
  reservationsEnabled: boolean;
  openingTime: string | null;
  closingTime: string | null;
  slotMinutes: number;
  slotCapacity: number;
  timeZone: string;
}

// ---- Leave-Now (FR-11..FR-16) ----

export interface LeaveNowResponse {
  sharingLocation: boolean;
  travelMinutes: number | null;
  /** false = a free straight-line estimate, true = real traffic data. The UI
   *  is honest about the difference rather than implying false precision. */
  trafficAware: boolean;
  trafficDelayMinutes: number | null;
  leaveBy: string | null;
  shouldLeaveNow: boolean;
  enRoute: boolean;
  graceExpiresAt: string | null;
  /** Everyone waiting gets a turn window — no location needed. */
  expectedTurnAt: string | null;
  turnWindowMinutes: number;
  venueLatitude: number | null;
  venueLongitude: number | null;
}

export interface GeocodeResult {
  label: string;
  latitude: number;
  longitude: number;
}

export interface VenueConfig {
  venueLatitude: number | null;
  venueLongitude: number | null;
  graceMinutes: number;
  /** 0 = mark a no-show instead of bumping back. */
  bumpPlaces: number;
  safetyBufferMinutes: number;
  // ---- V9: discovery + remote-joining policy ----
  /** Appear in the public restaurant search. */
  listedPublicly: boolean;
  allowRemoteJoin: boolean;
  /** The owner's radius — never above maxRemoteJoinMilesCeiling. */
  maxRemoteJoinMiles: number;
  allowQrJoin: boolean;
  /** Product-wide cap (50), sent by the server so the UI never hard-codes it. */
  maxRemoteJoinMilesCeiling: number;
}

// ---- V9: public discovery (PublicDtos.VenueSummary / VenueDetail) ----

export interface VenueSummary {
  businessName: string;
  queueName: string;
  /** The one identifier — the same token the QR code encodes. */
  joinToken: string;
  open: boolean;
  partiesWaiting: number;
  estimatedWaitMinutes: number;
  estimatedWaitMaxMinutes: number;
  /** Null when no location was shared, or the venue has no coordinates. */
  distanceMiles: number | null;
  remoteJoinAllowed: boolean;
  maxRemoteJoinMiles: number;
  qrJoinAllowed: boolean;
  venueLatitude: number | null;
  venueLongitude: number | null;
  tooFarToJoinRemotely: boolean;
  reservationsEnabled: boolean;
}

/** What a scanned restaurant QR resolves to (PublicDtos.RestaurantDetail). */
export interface RestaurantDetail {
  businessName: string;
  publicToken: string;
  /** Empty is valid: the restaurant exists but is running no lines today. */
  queues: VenueSummary[];
}

export interface VenueDetail {
  venue: VenueSummary;
  reservationsEnabled: boolean;
}

/**
 * One row of "what's near me" — a NowServing tenant OR an OpenStreetMap
 * point of interest, told apart by `onNowServing`. Everything after
 * `address` is null for an external place: there is no queue behind it, so
 * it must never render a "Join Waitlist" button.
 */
export interface NearbyPlaceResponse {
  name: string;
  distanceMiles: number | null;
  cuisine: string | null;
  address: string | null;
  onNowServing: boolean;
  joinToken: string | null;
  joinableNow: boolean | null;
  remoteJoinEnabled: boolean | null;
  currentWaitMinutes: number | null;
  partiesWaiting: number | null;
  /** External-place-only, derived from OSM's opening_hours tag at request
   *  time. Null for a NowServing venue, which uses joinableNow instead. */
  openingStatus: 'OPEN_NOW' | 'CLOSED' | 'UNKNOWN' | null;
  /** NowServing-only; null for an external place. */
  reservationsEnabled: boolean | null;
  /** Populated for BOTH sources so every card can offer Directions from real
   *  coordinates. Null only when an owner hasn't set a venue location. */
  latitude: number | null;
  longitude: number | null;
}

/** How the customer asked to be reached (entity/NotifyChannel.java). */
export type NotifyChannel = 'PUSH' | 'SMS' | 'NONE';

// ---- errors (GlobalExceptionHandler.ApiError) ----

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  fieldErrors: Record<string, string> | null;
}
