package com.lagu.platform.booking.dto;

/**
 * Body for the three admin settlement actions.
 *
 * @param invoiceNumber optional on invoice — supply one to reconcile against whatever system
 *                      actually issues the document, or leave it null to have one generated.
 *                      Ignored by the other two actions.
 * @param note          a payment reference, an invoice-run id, or — for a waiver, where it is
 *                      required rather than optional — the reason revenue is being forgone.
 */
public record SettlementActionRequest(String invoiceNumber, String note) {
}
