-- AlterEnum
ALTER TYPE "CostCategory" ADD VALUE 'LABOR';

-- AlterTable
ALTER TABLE "Parcel" ADD COLUMN     "irrigatedAreaHa" DOUBLE PRECISION;

-- AlterTable
ALTER TABLE "CropSeason" ADD COLUMN     "expectedHarvestDate" TIMESTAMP(3);

-- CreateTable
CREATE TABLE "MachineLocation" (
    "id" BIGSERIAL NOT NULL,
    "workSessionId" UUID NOT NULL,
    "machineId" UUID,
    "lat" DOUBLE PRECISION NOT NULL,
    "lng" DOUBLE PRECISION NOT NULL,
    "headingDeg" DOUBLE PRECISION,
    "speedKmh" DOUBLE PRECISION,
    "accuracyM" DOUBLE PRECISION,
    "recordedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "MachineLocation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "CropRecommendation" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "year" INTEGER NOT NULL,
    "crop" "CropCode" NOT NULL,
    "score" INTEGER NOT NULL,
    "reasons" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "CropRecommendation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WeatherData" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "date" DATE NOT NULL,
    "tMaxC" DOUBLE PRECISION NOT NULL,
    "tMinC" DOUBLE PRECISION NOT NULL,
    "precipitationMm" DOUBLE PRECISION NOT NULL,
    "humidityPct" DOUBLE PRECISION,
    "windMaxKmh" DOUBLE PRECISION,
    "et0Mm" DOUBLE PRECISION,
    "source" TEXT NOT NULL DEFAULT 'open-meteo',
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "WeatherData_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Observation" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "authorId" UUID NOT NULL,
    "text" TEXT NOT NULL,
    "lat" DOUBLE PRECISION,
    "lng" DOUBLE PRECISION,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Observation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Photo" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "observationId" UUID,
    "authorId" UUID NOT NULL,
    "storageKey" TEXT NOT NULL,
    "contentType" TEXT NOT NULL,
    "sizeBytes" INTEGER NOT NULL,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Photo_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Income" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "year" INTEGER NOT NULL,
    "amount" DECIMAL(12,2) NOT NULL,
    "currency" TEXT NOT NULL DEFAULT 'RON',
    "source" TEXT,
    "date" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Income_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "MachineLocation_workSessionId_recordedAt_idx" ON "MachineLocation"("workSessionId", "recordedAt" DESC);

-- CreateIndex
CREATE INDEX "MachineLocation_recordedAt_idx" ON "MachineLocation"("recordedAt");

-- CreateIndex
CREATE UNIQUE INDEX "CropRecommendation_parcelId_year_crop_key" ON "CropRecommendation"("parcelId", "year", "crop");

-- CreateIndex
CREATE UNIQUE INDEX "WeatherData_parcelId_date_key" ON "WeatherData"("parcelId", "date");

-- CreateIndex
CREATE INDEX "Observation_parcelId_createdAt_idx" ON "Observation"("parcelId", "createdAt" DESC);

-- CreateIndex
CREATE UNIQUE INDEX "Photo_storageKey_key" ON "Photo"("storageKey");

-- CreateIndex
CREATE INDEX "Photo_parcelId_createdAt_idx" ON "Photo"("parcelId", "createdAt" DESC);

-- CreateIndex
CREATE INDEX "Income_parcelId_year_idx" ON "Income"("parcelId", "year");

-- AddForeignKey
ALTER TABLE "MachineLocation" ADD CONSTRAINT "MachineLocation_workSessionId_fkey" FOREIGN KEY ("workSessionId") REFERENCES "WorkSession"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MachineLocation" ADD CONSTRAINT "MachineLocation_machineId_fkey" FOREIGN KEY ("machineId") REFERENCES "Machine"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CropRecommendation" ADD CONSTRAINT "CropRecommendation_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WeatherData" ADD CONSTRAINT "WeatherData_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Observation" ADD CONSTRAINT "Observation_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Observation" ADD CONSTRAINT "Observation_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Photo" ADD CONSTRAINT "Photo_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Photo" ADD CONSTRAINT "Photo_observationId_fkey" FOREIGN KEY ("observationId") REFERENCES "Observation"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Photo" ADD CONSTRAINT "Photo_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Income" ADD CONSTRAINT "Income_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

