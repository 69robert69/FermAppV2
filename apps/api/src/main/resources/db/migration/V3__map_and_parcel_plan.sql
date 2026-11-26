-- AlterEnum
-- This migration adds more than one value to an enum.
-- With PostgreSQL versions 11 and earlier, this is not possible
-- in a single migration. This can be worked around by creating
-- multiple migrations, each migration adding only one value to
-- the enum.


ALTER TYPE "TaskType" ADD VALUE 'SOIL_PREP';
ALTER TYPE "TaskType" ADD VALUE 'SEED_PURCHASE';
ALTER TYPE "TaskType" ADD VALUE 'SOWING';
ALTER TYPE "TaskType" ADD VALUE 'EMERGENCE_CHECK';
ALTER TYPE "TaskType" ADD VALUE 'HARVEST_PREP';

-- AlterTable
ALTER TABLE "Parcel" ADD COLUMN     "lastCrop" TEXT,
ADD COLUMN     "plannedCrop" "CropCode";

-- AlterTable
ALTER TABLE "Task" ADD COLUMN     "planId" UUID,
ADD COLUMN     "planStepKey" TEXT;

-- CreateTable
CREATE TABLE "ParcelPlan" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "year" INTEGER NOT NULL,
    "crop" "CropCode" NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "trigger" TEXT NOT NULL,
    "data" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ParcelPlan_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "ParcelPlan_parcelId_active_year_idx" ON "ParcelPlan"("parcelId", "active", "year");

-- CreateIndex
CREATE INDEX "ParcelPlan_parcelId_createdAt_idx" ON "ParcelPlan"("parcelId", "createdAt" DESC);

-- CreateIndex
CREATE INDEX "Task_planId_idx" ON "Task"("planId");

-- AddForeignKey
ALTER TABLE "ParcelPlan" ADD CONSTRAINT "ParcelPlan_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Task" ADD CONSTRAINT "Task_planId_fkey" FOREIGN KEY ("planId") REFERENCES "ParcelPlan"("id") ON DELETE SET NULL ON UPDATE CASCADE;

