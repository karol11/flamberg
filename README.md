# Flamberg
A high/low level lightweight programming language.

## Features
- Compiled to machine code. If needed - low level, otherwise - managed
- No GC! [Litetime management built atop of master-shared-weak references](https://github.com/karol11/ltm).
- Modular.
- Object-oriented, imperative, functional.
- Strong typing with global type inference.
- Lightweight indentation-based syntax.
- Simple. Seven syntax elements: constant, function, dispatcher, call, import, name, type.

## Samples

### Hello world
```Python
"Hello world {2*2}!"
```

### Functions
```Python
square =: x
  x * x
  
echo square(4)
echo square(3.14)
```

### Data structures
```Python
Person = : name age ##
[]
  Person "Andrey" 45
  Person "Katya"  45-7
  Person "Polina" 14
  Person "Tony"    7
.each: i "{i.name} of {i.age}"
.echo
```

### Low level
Invert the most significant bit in a byte at address 0x12345.
```Python
+unsafe
ptr(0x12345).byte ^= 0x80
```

### Fizz-Buzz
```Python
(1..101).each: i
  if i%3:
     if i%5 :i.toString :"buzz"
  else:
     if i%5 :"fizz"     :"fizzbuzz"
.echo
```

### In PHP boots
```Python
mySql.connect dbName user password
.query "
     select name, id
     from users
     limit 100, {page}
.map: u "
     <li id="{u.id}">
         {u.name}
     </li>
.echo
```

### Classes and objects
```Python
+canvas

Circle = : x y r color #
   paint: canvas
      canvas.fillEllipse x-r  y-r  x+r  y+r  color

Rectangle = : x y w h color #
   paint: canvas
      canvas.fillRect x y x+w y+h color

Group = x y items #
   paint: canvas
       canvas.translate x y
       items.each: i
           i.paint canvas
       canvas.translate -x -y

root = Group 10 10 []
   Circle 100 100 40 color.red
   Group 100 100 []
      Rectangle 5 5 20 40 color.black
      Circle 20 20 20 color.red

root.paint desktopWindow.canvas
```

[More details on Wiki](https://github.com/karol11/flamberg/wiki)
