const ensemble = require('./ensemble');

module.exports.handle = function(request) {
  return ensemble.createEnsembleResult(request);
};
