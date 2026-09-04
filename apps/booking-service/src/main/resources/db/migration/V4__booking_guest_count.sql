-- How many people the event expects, on the booking itself.
--
-- A vendor cannot price a job without it. `CreateBookingRequest` carried a listing, a date, an
-- optional event id and free text, so the headcount — the first thing a caterer or a venue asks —
-- had nowhere to go but the inquiry message. events-ui was seeding that message with a sentence
-- ("We're expecting around 250 guests.") precisely because this column did not exist: visible and
-- editable, so honest, but a number the platform could not read, filter, or show a vendor as a
-- field. Every quote that came back as "how many guests?" instead of a price is this gap.
--
-- Nullable, and deliberately: a booking raised from the marketplace with no event attached has no
-- headcount to state, and an event whose type does not define a guest-count field cannot supply
-- one either. Absent means "not stated", which is different from zero and must stay different —
-- a vendor reading 0 covers would quote nothing at all.
ALTER TABLE booking
    ADD COLUMN guest_count INTEGER;

-- Nonsense is worse than absence here: a negative headcount is a data-entry slip and a zero one
-- reads as "nobody is coming", both of which a vendor would have to ring up to resolve. The DTO
-- validates the same bound; this is the one that holds when something other than the API writes.
ALTER TABLE booking
    ADD CONSTRAINT ck_booking_guest_count_positive
    CHECK (guest_count IS NULL OR guest_count > 0);
