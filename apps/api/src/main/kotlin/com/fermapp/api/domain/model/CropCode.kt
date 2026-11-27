package com.fermapp.api.domain.model

/** Crops in the catalog. Same values as the PostgreSQL enum type "CropCode". */
enum class CropCode {
    WHEAT,
    BARLEY,
    CORN,
    SUNFLOWER,
    RAPESEED,
    SOY,
    ALFALFA,
    OAT,
    POTATO,
    BEET,
    VEGETABLES,
}
