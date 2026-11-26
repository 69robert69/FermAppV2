-- The farm now works in euro. Amounts recorded in lei are converted once,
-- at 1 EUR = 4.97 RON. Back up the database before applying this migration.

-- Recorded expenses and incomes carry their currency, so only lei rows change.
UPDATE "CostEntry" SET "amount" = ROUND("amount" / 4.97, 2), "currency" = 'EUR' WHERE "currency" = 'RON';
UPDATE "Income" SET "amount" = ROUND("amount" / 4.97, 2), "currency" = 'EUR' WHERE "currency" = 'RON';

-- Season prices per tonne were entered in lei and have no currency column.
UPDATE "CropSeason"
SET "expectedPricePerT" = ROUND("expectedPricePerT" / 4.97, 2),
    "actualPricePerT" = ROUND("actualPricePerT" / 4.97, 2)
WHERE "expectedPricePerT" IS NOT NULL OR "actualPricePerT" IS NOT NULL;

-- AlterTable
ALTER TABLE "CostEntry" ALTER COLUMN "currency" SET DEFAULT 'EUR';
ALTER TABLE "Income" ALTER COLUMN "currency" SET DEFAULT 'EUR';

-- CreateTable
CREATE TABLE "EconomicDocument" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "key" TEXT NOT NULL,
    "parcelId" UUID,
    "year" INTEGER,
    "data" JSONB NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "EconomicDocument_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "EconomicDocument_farmId_year_idx" ON "EconomicDocument"("farmId", "year");

-- CreateIndex
CREATE UNIQUE INDEX "EconomicDocument_farmId_key_key" ON "EconomicDocument"("farmId", "key");

-- AddForeignKey
ALTER TABLE "EconomicDocument" ADD CONSTRAINT "EconomicDocument_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;
