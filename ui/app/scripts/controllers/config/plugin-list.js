/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global glowroot, angular */

glowroot.controller('ConfigPluginListCtrl', [
  '$scope',
  '$location',
  '$http',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, queryStrings, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.pluginQueryString = function (plugin) {
      var query = {};
      if ($scope.serverId) {
        query.serverId = $scope.serverId;
      }
      query.pluginId = plugin.id;
      return queryStrings.encodeObject(query);
    };

    $http.get('backend/config/plugins?server-id=' + encodeURIComponent($scope.serverId))
        .success(function (data) {
          $scope.loaded = true;
          $scope.plugins = [];
          var pluginsWithNoConfig = [];
          angular.forEach(data, function (plugin) {
            if (plugin.hasConfig) {
              $scope.plugins.push(plugin);
            } else {
              pluginsWithNoConfig.push(plugin.name);
            }
          });
          $scope.pluginsWithNoConfig = pluginsWithNoConfig.join(', ');
        })
        .error(httpErrors.handler($scope));
  }
]);
