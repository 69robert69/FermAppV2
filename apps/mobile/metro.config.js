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
// The shared package is loaded directly from source.
config.resolver.extraNodeModules = {
  '@fermapp/domain': path.resolve(workspaceRoot, 'packages/domain'),
};

// Shapefile archives are opened with JSZip, because this package does not work on the phone.
config.resolver.resolveRequest = (context, moduleName, platform) =>
  moduleName === 'but-unzip' ? { type: 'empty' } : context.resolveRequest(context, moduleName, platform);

module.exports = config;
