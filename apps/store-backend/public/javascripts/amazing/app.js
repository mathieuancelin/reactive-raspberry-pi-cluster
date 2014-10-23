requirejs.config({
    baseUrl: '/assets/javascripts/amazing/app',
    paths: {
        'jquery': '../../vendor/jquery',
        'app': '../app',
        'handlebars': '../../vendor/handlebars-v1.3.0',
        'foundation' : '../../foundation/foundation',
        'foundationAlert' : '../../foundation/foundation.alert',
        'underscore' : '../../vendor/underscore-min',
        'sugar' : '../../vendor/sugar.min',
    },
    shim: {
        'jquery': {
            exports: 'jquery'
        },
        'handlebars': {
            exports: 'Handlebars'
        },
        'foundation': {
            deps: ['jquery'],
            exports: 'Foundation'
        },
        'foundationAlert': {
            deps: ['jquery', 'foundation'],
        },
        'underscore': {
            exports: '_'
        }
    },
    priority: ['jquery']
});
require(['jquery', 'foundation', 'foundationAlert', 'underscore', 'sugar'])