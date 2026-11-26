-- AlterTable
ALTER TABLE "Parcel" ADD COLUMN     "tillageHistory" JSONB,
ADD COLUMN     "tillageSystem" TEXT;

-- AlterTable
ALTER TABLE "Machine" ADD COLUMN     "tillageSystems" TEXT[] DEFAULT ARRAY[]::TEXT[];
