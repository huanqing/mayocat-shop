package org.mayocat.shop.catalog.model;

import java.io.Serializable;
import java.math.BigDecimal;

import org.mayocat.model.Identifiable;

/**
 * @version $Id$
 */
public interface Purchasable extends Identifiable, Serializable
{
    String getTitle();

    String getDescription();

    BigDecimal getUnitPrice();
}
