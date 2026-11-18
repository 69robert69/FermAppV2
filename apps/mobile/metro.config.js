const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
];
// Pachetul comun se încarcă direct din sursă.
config.resolver.extraNodeModules = {
  '@fermapp/domain': path.resolve(workspaceRoot, 'packages/domain'),
};

// Arhivele Shapefile se deschid cu JSZip, deoarece acest pachet nu funcționează pe telefon.
config.resolver.resolveRequest = (context, moduleName, platform) =>
  moduleName === 'but-unzip' ? { type: 'empty' } : context.resolveRequest(context, moduleName, platform);

module.exports = config;
