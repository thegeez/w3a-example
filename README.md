# w3a-example

Web application in Pedestal using [w3a]

More information on the blogpost: http://thegeez.net/2015/05/16/w3a_web_application_pedestal.html

[w3a]: https://github.com/thegeez/w3a

### Source
The most interesting file is src/w3a/example/service.clj

### Development
This uses an in-process/in-memory only database. In the `user` namespace, through `lein repl/cider` etc.:
```
   (go) ;; to start the component system, localhost:8080 will serve the site
   (reset) ;; to reset the whole component system
```

### Running production uberjar (for heroku):
```
   lein uberjar
   java -jar target/w3a-example-prod-standalone.jar PORT DB-URL
```

### Deploy on heroku
First time, provision a postgrest database (see https://devcenter.heroku.com/articles/heroku-postgresql):
```
   heroku addons:add heroku-postgresql:dev
```
Deploying:
```
   git push heroku
```

## About

Written by:
Gijs Stuurman / [@thegeez][twt] / [Blog][blog] / [GitHub][github]

[twt]: http://twitter.com/thegeez
[blog]: http://thegeez.net
[github]: https://github.com/thegeez

## License

Copyright © 2015 Gijs Stuurman

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
