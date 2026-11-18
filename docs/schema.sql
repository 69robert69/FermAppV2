-- Migrare: 20261118000000_init
-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateEnum
CREATE TYPE "Role" AS ENUM ('ADMIN', 'FARM_MANAGER', 'OPERATOR', 'AGRONOMIST');

-- CreateEnum
CREATE TYPE "CropCode" AS ENUM ('WHEAT', 'BARLEY', 'CORN', 'SUNFLOWER', 'RAPESEED', 'SOY', 'ALFALFA', 'OAT', 'POTATO', 'BEET', 'VEGETABLES');

-- CreateEnum
CREATE TYPE "MachineType" AS ENUM ('TRACTOR', 'COMBINE', 'SPRAYER', 'SEEDER', 'DISC', 'CULTIVATOR', 'SUBSOILER', 'TRAILER');

-- CreateEnum
CREATE TYPE "WorkType" AS ENUM ('PLOWING', 'DISCING', 'SUBSOILING', 'SOWING', 'FERTILIZING', 'SPRAYING', 'HARVESTING');

-- CreateEnum
CREATE TYPE "RunStatus" AS ENUM ('RUNNING', 'PAUSED', 'COMPLETED', 'CANCELLED');

-- CreateEnum
CREATE TYPE "TaskType" AS ENUM ('FERTILIZATION', 'HERBICIDE', 'FUNGICIDE', 'INSECTICIDE', 'PEST_CHECK', 'DISEASE_CHECK', 'MOISTURE_CHECK', 'LEAF_ANALYSIS', 'HARVEST');

-- CreateEnum
CREATE TYPE "TaskPriority" AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'URGENT');

-- CreateEnum
CREATE TYPE "TaskStatus" AS ENUM ('TODO', 'IN_PROGRESS', 'DONE');

-- CreateEnum
CREATE TYPE "IrrigationSystemType" AS ENUM ('REEL', 'PIVOT', 'LINEAR');

-- CreateEnum
CREATE TYPE "SoilStatus" AS ENUM ('PENDING', 'READY', 'FAILED');

-- CreateEnum
CREATE TYPE "AlertType" AS ENUM ('RAIN', 'STORM', 'HAIL', 'FROST', 'HEAT', 'WIND_SPRAYING');

-- CreateEnum
CREATE TYPE "AlertSeverity" AS ENUM ('INFO', 'WARNING', 'SEVERE');

-- CreateEnum
CREATE TYPE "CostCategory" AS ENUM ('SEED', 'FERTILIZER', 'PESTICIDE', 'FUEL', 'IRRIGATION', 'OTHER');

-- CreateEnum
CREATE TYPE "AiRole" AS ENUM ('USER', 'ASSISTANT');

-- CreateEnum
CREATE TYPE "IotDeviceKind" AS ENUM ('WEATHER_STATION', 'SOIL_MOISTURE', 'SOIL_TEMPERATURE', 'CONDUCTIVITY', 'FLOW_METER', 'TANK_LEVEL');

-- CreateEnum
CREATE TYPE "IotProtocol" AS ENUM ('MQTT', 'REST', 'WEBSOCKET');

-- CreateTable
CREATE TABLE "Farm" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "county" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Farm_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "User" (
    "id" UUID NOT NULL,
    "email" TEXT NOT NULL,
    "fullName" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "FarmMembership" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "role" "Role" NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "FarmMembership_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RefreshToken" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "familyId" UUID NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RefreshToken_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "PushDevice" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "token" TEXT NOT NULL,
    "platform" TEXT,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PushDevice_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "IdempotencyRecord" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "key" TEXT NOT NULL,
    "route" TEXT NOT NULL,
    "statusCode" INTEGER NOT NULL DEFAULT 0,
    "response" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "IdempotencyRecord_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Parcel" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "geometry" JSONB NOT NULL,
    "areaHa" DOUBLE PRECISION NOT NULL,
    "perimeterM" DOUBLE PRECISION NOT NULL,
    "centerLat" DOUBLE PRECISION NOT NULL,
    "centerLng" DOUBLE PRECISION NOT NULL,
    "locality" TEXT,
    "county" TEXT,
    "notes" TEXT,
    "archivedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Parcel_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "CropSeason" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "year" INTEGER NOT NULL,
    "crop" "CropCode" NOT NULL,
    "sowingDate" TIMESTAMP(3),
    "harvestDate" TIMESTAMP(3),
    "yieldT" DOUBLE PRECISION,
    "harvestMoisture" DOUBLE PRECISION,
    "expectedYieldTHa" DOUBLE PRECISION,
    "expectedPricePerT" DECIMAL(12,2),
    "actualPricePerT" DECIMAL(12,2),
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CropSeason_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SoilProfile" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "status" "SoilStatus" NOT NULL DEFAULT 'PENDING',
    "ph" DOUBLE PRECISION,
    "clayPct" DOUBLE PRECISION,
    "sandPct" DOUBLE PRECISION,
    "siltPct" DOUBLE PRECISION,
    "organicCarbonGKg" DOUBLE PRECISION,
    "organicMatterPct" DOUBLE PRECISION,
    "waterHoldingMmPerM" DOUBLE PRECISION,
    "texture" TEXT,
    "soilType" TEXT,
    "label" TEXT,
    "errorMessage" TEXT,
    "fetchedAt" TIMESTAMP(3),
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "SoilProfile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Machine" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "type" "MachineType" NOT NULL,
    "brand" TEXT,
    "model" TEXT,
    "powerHp" DOUBLE PRECISION,
    "avgConsumptionLh" DOUBLE PRECISION,
    "operatorId" UUID,
    "archivedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Machine_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WorkSession" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "machineId" UUID,
    "operatorId" UUID NOT NULL,
    "type" "WorkType" NOT NULL,
    "areaHa" DOUBLE PRECISION NOT NULL,
    "workingWidthM" DOUBLE PRECISION NOT NULL,
    "speedKmh" DOUBLE PRECISION NOT NULL,
    "efficiencyPct" DOUBLE PRECISION NOT NULL,
    "capacityHaH" DOUBLE PRECISION NOT NULL,
    "durationHours" DOUBLE PRECISION NOT NULL,
    "status" "RunStatus" NOT NULL DEFAULT 'RUNNING',
    "startedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "pausedAt" TIMESTAMP(3),
    "pausedMs" BIGINT NOT NULL DEFAULT 0,
    "completedAt" TIMESTAMP(3),
    "progressPct" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "fuelLiters" DOUBLE PRECISION,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "WorkSession_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Task" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "parcelId" UUID,
    "assigneeId" UUID,
    "type" "TaskType" NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT,
    "priority" "TaskPriority" NOT NULL DEFAULT 'MEDIUM',
    "status" "TaskStatus" NOT NULL DEFAULT 'TODO',
    "dueDate" TIMESTAMP(3) NOT NULL,
    "reminderSentAt" TIMESTAMP(3),
    "completedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Task_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "IrrigationRun" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "systemType" "IrrigationSystemType" NOT NULL,
    "routeLengthM" DOUBLE PRECISION NOT NULL,
    "speedMPerH" DOUBLE PRECISION NOT NULL,
    "doseMm" DOUBLE PRECISION,
    "durationHours" DOUBLE PRECISION NOT NULL,
    "status" "RunStatus" NOT NULL DEFAULT 'RUNNING',
    "startedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "pausedAt" TIMESTAMP(3),
    "pausedMs" BIGINT NOT NULL DEFAULT 0,
    "completedAt" TIMESTAMP(3),
    "progressPct" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "IrrigationRun_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WeatherAlert" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "type" "AlertType" NOT NULL,
    "severity" "AlertSeverity" NOT NULL,
    "title" TEXT NOT NULL,
    "message" TEXT NOT NULL,
    "startsAt" TEXT NOT NULL,
    "dedupeKey" TEXT NOT NULL,
    "source" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WeatherAlert_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SatelliteObservation" (
    "id" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "date" DATE NOT NULL,
    "ndvi" DOUBLE PRECISION NOT NULL,
    "ndre" DOUBLE PRECISION,
    "healthIndex" INTEGER NOT NULL,
    "healthClass" TEXT NOT NULL,
    "cloudPct" DOUBLE PRECISION,
    "source" TEXT NOT NULL DEFAULT 'sentinel-2-l2a',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SatelliteObservation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "CostEntry" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "parcelId" UUID NOT NULL,
    "year" INTEGER NOT NULL,
    "category" "CostCategory" NOT NULL,
    "amount" DECIMAL(12,2) NOT NULL,
    "currency" TEXT NOT NULL DEFAULT 'RON',
    "date" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CostEntry_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AiConversation" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "parcelId" UUID,
    "title" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AiConversation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AiMessage" (
    "id" UUID NOT NULL,
    "conversationId" UUID NOT NULL,
    "role" "AiRole" NOT NULL,
    "content" TEXT NOT NULL,
    "source" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AiMessage_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "IotDevice" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "parcelId" UUID,
    "name" TEXT NOT NULL,
    "kind" "IotDeviceKind" NOT NULL,
    "vendor" TEXT,
    "protocol" "IotProtocol" NOT NULL,
    "keyHash" TEXT NOT NULL,
    "lastSeenAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "IotDevice_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "IotReading" (
    "id" BIGSERIAL NOT NULL,
    "deviceId" UUID NOT NULL,
    "metric" TEXT NOT NULL,
    "value" DOUBLE PRECISION NOT NULL,
    "unit" TEXT,
    "recordedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "IotReading_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Notification" (
    "id" UUID NOT NULL,
    "farmId" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "data" JSONB,
    "readAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Notification_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

-- CreateIndex
CREATE INDEX "FarmMembership_userId_idx" ON "FarmMembership"("userId");

-- CreateIndex
CREATE UNIQUE INDEX "FarmMembership_farmId_userId_key" ON "FarmMembership"("farmId", "userId");

-- CreateIndex
CREATE UNIQUE INDEX "RefreshToken_tokenHash_key" ON "RefreshToken"("tokenHash");

-- CreateIndex
CREATE INDEX "RefreshToken_userId_idx" ON "RefreshToken"("userId");

-- CreateIndex
CREATE INDEX "RefreshToken_familyId_idx" ON "RefreshToken"("familyId");

-- CreateIndex
CREATE UNIQUE INDEX "PushDevice_token_key" ON "PushDevice"("token");

-- CreateIndex
CREATE INDEX "PushDevice_userId_idx" ON "PushDevice"("userId");

-- CreateIndex
CREATE INDEX "IdempotencyRecord_createdAt_idx" ON "IdempotencyRecord"("createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "IdempotencyRecord_userId_key_key" ON "IdempotencyRecord"("userId", "key");

-- CreateIndex
CREATE INDEX "Parcel_farmId_archivedAt_idx" ON "Parcel"("farmId", "archivedAt");

-- CreateIndex
CREATE INDEX "Parcel_farmId_updatedAt_idx" ON "Parcel"("farmId", "updatedAt");

-- CreateIndex
CREATE UNIQUE INDEX "Parcel_farmId_code_key" ON "Parcel"("farmId", "code");

-- CreateIndex
CREATE INDEX "CropSeason_parcelId_year_idx" ON "CropSeason"("parcelId", "year" DESC);

-- CreateIndex
CREATE UNIQUE INDEX "CropSeason_parcelId_year_key" ON "CropSeason"("parcelId", "year");

-- CreateIndex
CREATE UNIQUE INDEX "SoilProfile_parcelId_key" ON "SoilProfile"("parcelId");

-- CreateIndex
CREATE INDEX "Machine_farmId_idx" ON "Machine"("farmId");

-- CreateIndex
CREATE INDEX "WorkSession_farmId_status_idx" ON "WorkSession"("farmId", "status");

-- CreateIndex
CREATE INDEX "WorkSession_farmId_startedAt_idx" ON "WorkSession"("farmId", "startedAt" DESC);

-- CreateIndex
CREATE INDEX "WorkSession_parcelId_idx" ON "WorkSession"("parcelId");

-- CreateIndex
CREATE INDEX "Task_farmId_status_idx" ON "Task"("farmId", "status");

-- CreateIndex
CREATE INDEX "Task_status_dueDate_idx" ON "Task"("status", "dueDate");

-- CreateIndex
CREATE INDEX "IrrigationRun_farmId_status_idx" ON "IrrigationRun"("farmId", "status");

-- CreateIndex
CREATE INDEX "IrrigationRun_parcelId_idx" ON "IrrigationRun"("parcelId");

-- CreateIndex
CREATE INDEX "WeatherAlert_farmId_createdAt_idx" ON "WeatherAlert"("farmId", "createdAt" DESC);

-- CreateIndex
CREATE UNIQUE INDEX "WeatherAlert_parcelId_dedupeKey_key" ON "WeatherAlert"("parcelId", "dedupeKey");

-- CreateIndex
CREATE INDEX "SatelliteObservation_parcelId_date_idx" ON "SatelliteObservation"("parcelId", "date" DESC);

-- CreateIndex
CREATE UNIQUE INDEX "SatelliteObservation_parcelId_date_key" ON "SatelliteObservation"("parcelId", "date");

-- CreateIndex
CREATE INDEX "CostEntry_farmId_year_idx" ON "CostEntry"("farmId", "year");

-- CreateIndex
CREATE INDEX "CostEntry_parcelId_year_idx" ON "CostEntry"("parcelId", "year");

-- CreateIndex
CREATE INDEX "AiConversation_farmId_userId_updatedAt_idx" ON "AiConversation"("farmId", "userId", "updatedAt" DESC);

-- CreateIndex
CREATE INDEX "AiMessage_conversationId_createdAt_idx" ON "AiMessage"("conversationId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "IotDevice_keyHash_key" ON "IotDevice"("keyHash");

-- CreateIndex
CREATE INDEX "IotDevice_farmId_idx" ON "IotDevice"("farmId");

-- CreateIndex
CREATE INDEX "IotReading_deviceId_recordedAt_idx" ON "IotReading"("deviceId", "recordedAt" DESC);

-- CreateIndex
CREATE UNIQUE INDEX "IotReading_deviceId_metric_recordedAt_key" ON "IotReading"("deviceId", "metric", "recordedAt");

-- CreateIndex
CREATE INDEX "Notification_userId_createdAt_idx" ON "Notification"("userId", "createdAt" DESC);

-- AddForeignKey
ALTER TABLE "FarmMembership" ADD CONSTRAINT "FarmMembership_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "FarmMembership" ADD CONSTRAINT "FarmMembership_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RefreshToken" ADD CONSTRAINT "RefreshToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PushDevice" ADD CONSTRAINT "PushDevice_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Parcel" ADD CONSTRAINT "Parcel_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CropSeason" ADD CONSTRAINT "CropSeason_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SoilProfile" ADD CONSTRAINT "SoilProfile_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Machine" ADD CONSTRAINT "Machine_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Machine" ADD CONSTRAINT "Machine_operatorId_fkey" FOREIGN KEY ("operatorId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkSession" ADD CONSTRAINT "WorkSession_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkSession" ADD CONSTRAINT "WorkSession_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkSession" ADD CONSTRAINT "WorkSession_machineId_fkey" FOREIGN KEY ("machineId") REFERENCES "Machine"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkSession" ADD CONSTRAINT "WorkSession_operatorId_fkey" FOREIGN KEY ("operatorId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Task" ADD CONSTRAINT "Task_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Task" ADD CONSTRAINT "Task_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Task" ADD CONSTRAINT "Task_assigneeId_fkey" FOREIGN KEY ("assigneeId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "IrrigationRun" ADD CONSTRAINT "IrrigationRun_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "IrrigationRun" ADD CONSTRAINT "IrrigationRun_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WeatherAlert" ADD CONSTRAINT "WeatherAlert_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WeatherAlert" ADD CONSTRAINT "WeatherAlert_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SatelliteObservation" ADD CONSTRAINT "SatelliteObservation_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CostEntry" ADD CONSTRAINT "CostEntry_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CostEntry" ADD CONSTRAINT "CostEntry_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "AiConversation" ADD CONSTRAINT "AiConversation_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "AiConversation" ADD CONSTRAINT "AiConversation_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "AiMessage" ADD CONSTRAINT "AiMessage_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "AiConversation"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "IotDevice" ADD CONSTRAINT "IotDevice_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "IotDevice" ADD CONSTRAINT "IotDevice_parcelId_fkey" FOREIGN KEY ("parcelId") REFERENCES "Parcel"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "IotReading" ADD CONSTRAINT "IotReading_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "IotDevice"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Notification" ADD CONSTRAINT "Notification_farmId_fkey" FOREIGN KEY ("farmId") REFERENCES "Farm"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Notification" ADD CONSTRAINT "Notification_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;


-- Migrare: 20261119000000_parcel_intelligence
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


-- Migrare: 20261120000000_map_and_parcel_plan
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

