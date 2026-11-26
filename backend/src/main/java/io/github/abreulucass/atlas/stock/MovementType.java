package io.github.abreulucass.atlas.stock;

/**
 * What a movement does to a balance: {@code IN} adds, {@code OUT} subtracts, {@code ADJUSTMENT} adds a signed delta;
 * {@code TRANSFER_OUT} and {@code TRANSFER_IN} are the two halves of a transfer between warehouses.
 */
public enum MovementType {

	IN, OUT, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN

}
